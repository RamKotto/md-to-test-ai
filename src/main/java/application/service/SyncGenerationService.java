package application.service;

import application.config.AppProperties;
import application.dto.Dtos.Clarification;
import application.dto.Dtos.ClarificationType;
import application.dto.Dtos.TestCase;
import application.exception.GenerationException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ядро генерации тест-кейсов: сборка промптов, вызов LLM, разбор и проверка ответа модели.
 * <p>
 * Конвейер обработки ответа: снятие обёртки {@code ```json}, парсинг JSON, проверка ключей
 * {@code testCases} и {@code clarifications}, починка и фильтрация тест-кейсов
 * ({@link #validateAndRepairTestCases}), проверка уточнений, санитизация конкретики, которой нет
 * в документе ({@link #sanitizeAgainstDocument}), объединение и дедупликация уточнений
 * ({@link #dedupeClarifications}). Класс не хранит состояния между запросами.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncGenerationService {

    /** Допустимые значения поля {@code type} тест-кейса. */
    private static final Set<String> ALLOWED_TYPES =
            Set.of("UI", "API", "E2E", "NEGATIVE", "SECURITY", "PERFORMANCE");

    /** Допустимые значения поля {@code priority} тест-кейса. */
    private static final Set<String> ALLOWED_PRIORITIES =
            Set.of("P0", "P1", "P2", "P3");

    /**
     * Допустимый формат id: {@code TC-<тип>-<номер не короче 3 цифр>}. Для типов допускаются и полные,
     * и сокращённые имена: NEG, SEC, PERF.
     */
    private static final Pattern ID_PATTERN =
            Pattern.compile("^TC-(UI|API|E2E|NEG|NEGATIVE|SEC|SECURITY|PERF|PERFORMANCE)-\\d{3,}$");

    /** HTTP-методы в документе; используются только для подсказки модели в {@link #extractAllowedTokens}. */
    private static final Pattern HTTP_METHOD =
            Pattern.compile("\\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\b");

    /**
     * Трёхзначные HTTP-коды 1xx–5xx как отдельные слова. Код обоснован только если он есть в документе,
     * блоке требования или ответах пользователя; белого списка распространённых кодов нет.
     */
    private static final Pattern CONCRETE_HTTP_CODE =
            Pattern.compile("\\b([1-5]\\d{2})\\b");

    /** snake_case идентификаторы (например, {@code error_code}). Проверяются на обоснованность. */
    private static final Pattern CONCRETE_FIELD_NAME =
            Pattern.compile("\\b([a-z][a-z0-9]*_[a-z0-9_]+)\\b");

    /** Имена полей, разрешённые без проверки по документу (часто встречаются в примерах API). */
    private static final Set<String> FIELD_NAME_WHITELIST = Set.of(
            "utc_offset_seconds", "snake_case", "requirement_ref",
            "error_message", "error_code", "request_id", "response_time",
            "temperature_2m", "wind_speed_10m", "relative_humidity_2m",
            "minutely_15", "forecast_days", "best_match"
    );

    /** Числа от трёх цифр (лимиты, значения, коды). Проверяются на обоснованность. */
    private static final Pattern CONCRETE_NUMBER_LIMIT =
            Pattern.compile("\\b(\\d{3,})\\b");

    /** Абсолютный http(s)-URL до пробела, скобки, кавычки или обратного апострофа. */
    private static final Pattern ABSOLUTE_URL =
            Pattern.compile("https?://[^\\s)\\]\"'`]+");

    /**
     * Абсолютный URL внутри текста. Такие фрагменты защищаются от нормализации пробелов и запятых,
     * иначе параметры вида {@code latitude=52.52,48.85} испортятся.
     */
    private static final Pattern URL_IN_TEXT =
            Pattern.compile("https?://\\S+");

    /** Число с пробелами или неразрывными пробелами между группами цифр, например {@code 10 000}. */
    private static final Pattern SPACED_NUMBER =
            Pattern.compile("\\b\\d{1,3}(?:[\\s\\u00A0]\\d{3})+\\b");

    /**
     * Query-параметр вида {@code name=value} (в том числе в относительном пути и внутри абсолютных URL).
     * Вырезается целиком, если значение не обосновано.
     */
    private static final Pattern QUERY_PARAM =
            Pattern.compile("([?&][a-zA-Z0-9_\\-]+)=([^&\\s\"'`]*)");

    /** Начало query-параметра без префикса {@code ?}/{@code &}; проверяет, что за {@code &} идёт параметр. */
    private static final Pattern QUERY_PARAM_NEXT =
            Pattern.compile("[a-zA-Z0-9_\\-]+=");

    /** Заглушка ожидаемого результата, если санитизация полностью вырезала текст. */
    private static final String NEUTRAL_EXPECTED_RESULT =
            "Поведение соответствует требованиям (см. уточнение)";

    /** Заглушка шага, если санитизация полностью вырезала текст. */
    private static final String NEUTRAL_STEP_SUFFIX =
            "(точное поведение уточняется)";

    /** Замена для вырезанного числа в тексте. */
    private static final String NEUTRAL_NUMBER =
            "[значение из ответа на уточнение]";

    /** Замена для HTTP-кода, которого нет в документе: код описывается словами, а не указывается. */
    private static final String NEUTRAL_HTTP_CODE =
            "[код ответа уточняется]";

    private final OpenRouterClient openRouter;
    private final ObjectMapper mapper;
    private final AppProperties appProps;

    /**
     * Результат генерации или обработки ответов.
     *
     * @param testCases      проверенные тест-кейсы
     * @param clarifications уточнения: модели (после дедупликации) и санитайзерные
     *                       ({@code UNGROUNDED_ASSUMPTION})
     */
    public record GenerationResult(List<TestCase> testCases, List<Clarification> clarifications) {
    }

    /**
     * Генерирует тест-кейсы для Markdown-документа.
     * <p>
     * Системный промпт получается подстановкой JSON-схемы в шаблон {@code app.prompts.generate}.
     * В пользовательский текст добавляется раздел «Разрешённые конкретные значения» с URL, методами,
     * кодами, полями и числами, найденными в документе.
     *
     * @param markdown исходный Markdown-документ
     * @return проверенный результат (см. {@link GenerationResult})
     * @throws GenerationException если OpenRouter вернул ошибку, ответ невалиден или все тест-кейсы отклонены
     */
    public GenerationResult generate(String markdown) {
        String prompt = appProps.getPrompts().getGenerate()
                .replace("{{schema}}", appProps.getPrompts().getJsonSchema());
        String userText = markdown + "\n\n## Разрешённые конкретные значения (из документа)\n"
                + extractAllowedTokens(markdown);
        String response = openRouter.complete(prompt, userText);
        return parseResponse(response, markdown, null);
    }

    /**
     * Обновляет тест-кейсы по ответам пользователя на уточнения.
     * <p>
     * Системный промпт — {@code app.prompts.answers} с подставленной схемой. В пользовательский текст
     * передаются документ, предыдущие тест-кейсы и ответы. Ответы считаются источником обоснования:
     * конкретика из них не вырезается, а уточнения {@code UNGROUNDED_ASSUMPTION} при ответах не создаются.
     *
     * @param markdown     исходный Markdown-документ
     * @param existingJson JSON текущих тест-кейсов из сессии
     * @param answersJson  JSON ответов пользователя
     * @return проверенный результат (см. {@link GenerationResult})
     * @throws GenerationException если OpenRouter вернул ошибку, ответ невалиден или все тест-кейсы отклонены
     */
    public GenerationResult answer(String markdown, String existingJson, String answersJson) {
        String prompt = appProps.getPrompts().getAnswers()
                .replace("{{schema}}", appProps.getPrompts().getJsonSchema());
        String userText = "## Документ\n" + markdown
                + "\n\n## Ранее сгенерированные тест-кейсы\n" + existingJson
                + "\n\n## Ответы на уточнения\n" + answersJson;
        String response = openRouter.complete(prompt, userText);
        return parseResponse(response, markdown, answersJson);
    }

    /**
     * Разбирает ответ модели и применяет все проверки.
     * <p>
     * Снимается обёртка {@code ```json ... ```} (или {@code ``` ... ```}, без учёта регистра {@code json}),
     * если она есть, и текст парсится как JSON.
     * Обязательны ключи {@code testCases} и {@code clarifications}. Уточнения разбираются через
     * {@link #parseClarifications}: неизвестный тип становится {@code MISSING_REQUIREMENT}. Затем выполняются
     * {@link #validateAndRepairTestCases}, {@link #validateClarifications}, {@link #sanitizeAgainstDocument}
     * и {@link #dedupeClarifications} (только для вопросов; {@code UNGROUNDED_ASSUMPTION} добавляются
     * в результат без слияния). Любая ошибка разбора, кроме {@link GenerationException},
     * оборачивается в {@link GenerationException}.
     *
     * @param json             сырой текст ответа модели
     * @param sourceMarkdown   исходный документ, по которому проверяется конкретика
     * @param groundingAnswers JSON ответов пользователя или {@code null} при первичной генерации
     * @return проверенный результат
     * @throws GenerationException если JSON невалиден, нет обязательных ключей или все тест-кейсы отклонены
     */
    private GenerationResult parseResponse(String json, String sourceMarkdown, String groundingAnswers) {
        try {
            String cleaned = json.trim()
                    .replaceAll("(?i)^```(?:json)?\\s*", "")
                    .replaceAll("\\s*```$", "")
                    .trim();
            Map<String, Object> map = mapper.readValue(cleaned, new TypeReference<>() {
            });

            if (!map.containsKey("testCases") || !map.containsKey("clarifications")) {
                throw new GenerationException(
                        "Ответ модели не содержит обязательных ключей testCases/clarifications");
            }

            List<TestCase> rawCases = mapper.convertValue(
                    map.getOrDefault("testCases", List.of()),
                    new TypeReference<List<TestCase>>() {
                    });
            List<Clarification> modelClarifications = parseClarifications(
                    map.getOrDefault("clarifications", List.of()));

            List<TestCase> validated = validateAndRepairTestCases(rawCases);
            List<Clarification> validClarifications = validateClarifications(modelClarifications);

            List<Clarification> groundingClarifications = new ArrayList<>();
            List<TestCase> sanitized = sanitizeAgainstDocument(
                    validated, sourceMarkdown, groundingAnswers, groundingClarifications);

            List<Clarification> all = new ArrayList<>(validClarifications);
            all.addAll(groundingClarifications);
            // UNGROUNDED_ASSUMPTION не сливаются с вопросами к пользователю: тип и записи сохраняются
            List<Clarification> questions = all.stream()
                    .filter(c -> c.type() != ClarificationType.UNGROUNDED_ASSUMPTION)
                    .toList();
            List<Clarification> ungrounded = all.stream()
                    .filter(c -> c.type() == ClarificationType.UNGROUNDED_ASSUMPTION)
                    .toList();
            List<Clarification> result = new ArrayList<>(dedupeClarifications(questions));
            result.addAll(ungrounded);

            return new GenerationResult(sanitized, result);
        } catch (GenerationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Ошибка парсинга JSON: {}", json, e);
            throw new GenerationException("Модель вернула невалидный JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Разбирает уточнения ответа модели. Элементы {@code null} пропускаются. Отсутствующий тип даёт
     * {@code MISSING_REQUIREMENT}; неизвестный тип тоже заменяется на {@code MISSING_REQUIREMENT}
     * с записью в лог, а не роняет весь ответ.
     *
     * @param raw значение ключа {@code clarifications} из ответа модели
     * @return уточнения с нормализованным типом
     */
    private List<Clarification> parseClarifications(Object raw) {
        List<Map<String, Object>> items = mapper.convertValue(raw,
                new TypeReference<List<Map<String, Object>>>() {
                });
        List<Clarification> out = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            if (item == null) {
                continue;
            }
            out.add(new Clarification(
                    asText(item.get("requirementRef")),
                    asText(item.get("question")),
                    asText(item.get("reason")),
                    parseClarificationType(item.get("type"))));
        }
        return out;
    }

    /**
     * Приводит тип уточнения из ответа модели к {@link ClarificationType}.
     *
     * @param value значение поля {@code type}; может быть {@code null}
     * @return распознанный тип либо {@code MISSING_REQUIREMENT}
     */
    private ClarificationType parseClarificationType(Object value) {
        if (value == null) {
            return ClarificationType.MISSING_REQUIREMENT;
        }
        try {
            return ClarificationType.valueOf(value.toString().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Неизвестный type уточнения '{}' — заменён на MISSING_REQUIREMENT", value);
            return ClarificationType.MISSING_REQUIREMENT;
        }
    }

    /**
     * Преобразует значение JSON в строку.
     *
     * @param value значение поля, может быть {@code null}
     * @return строковое представление или {@code null}
     */
    private String asText(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * Проверяет и исправляет тест-кейсы ответа модели.
     * <ul>
     *   <li>{@code null}-элемент пропускается;</li>
     *   <li>id пустой или не соответствует {@link #ID_PATTERN} заменяется на {@code TC-<тип>-NNN};
     *       повторный id тоже заменяется новым; новый номер не совпадает ни с одним id из ответа модели;</li>
     *   <li>type вне {@link #ALLOWED_TYPES} заменяется на {@code UI};</li>
     *   <li>пустой title — тест-кейс пропускается; title длиннее 200 символов обрезается;</li>
     *   <li>некорректный requirementRef заменяется на {@code N/A};</li>
     *   <li>пустые строки в steps удаляются; если шагов не осталось или expectedResult пуст,
     *       тест-кейс пропускается;</li>
     *   <li>priority вне {@link #ALLOWED_PRIORITIES} заменяется на {@code P2}.</li>
     * </ul>
     * Все замены и пропуски пишутся в лог на уровне WARN.
     *
     * @param cases тест-кейсы из ответа модели
     * @return тест-кейсы, прошедшие проверку
     * @throws GenerationException если входной список не пуст, но ни один элемент не прошёл проверку
     */
    private List<TestCase> validateAndRepairTestCases(List<TestCase> cases) {
        // Все id, которые модель вернула в этом ответе: автогенерация их не занимает
        Set<String> reservedIds = new HashSet<>();
        for (TestCase tc : cases) {
            if (tc != null && tc.id() != null) {
                reservedIds.add(tc.id());
            }
        }
        Set<String> seenIds = new HashSet<>();
        List<TestCase> repaired = new ArrayList<>(cases.size());
        int autoSeq = 1;

        for (TestCase tc : cases) {
            if (tc == null) {
                log.warn("Пустой элемент testCases пропущен");
                continue;
            }
            String id = tc.id();
            if (isBlank(id) || !ID_PATTERN.matcher(id).matches()) {
                String type = safeType(tc.type());
                String newId;
                do {
                    newId = "TC-" + typeShort(type) + "-" + String.format("%03d", autoSeq++);
                } while (reservedIds.contains(newId) || seenIds.contains(newId));
                log.warn("Некорректный id '{}' заменён на '{}'", tc.id(), newId);
                id = newId;
            }
            if (!seenIds.add(id)) {
                String type = safeType(tc.type());
                String newId;
                do {
                    newId = "TC-" + typeShort(type) + "-" + String.format("%03d", autoSeq++);
                } while (reservedIds.contains(newId) || seenIds.contains(newId));
                seenIds.add(newId);
                log.warn("Дубликат id '{}' заменён на '{}'", id, newId);
                id = newId;
            }

            String type = tc.type();
            if (isBlank(type) || !ALLOWED_TYPES.contains(type)) {
                log.warn("Недопустимый type '{}' для {} — заменён на UI", type, id);
                type = "UI";
            }

            String title = nullSafe(tc.title());
            if (title.isBlank()) {
                log.warn("Тест-кейс {} пропущен: пустой title", id);
                continue;
            }
            if (title.length() > 200) {
                title = title.substring(0, 200).trim();
            }

            String requirementRef = tc.requirementRef();
            if (!isValidRequirementRef(requirementRef)) {
                log.warn("Некорректный requirementRef '{}' для {} — заменён на N/A", requirementRef, id);
                requirementRef = "N/A";
            }

            List<String> preconditions = tc.preconditions() == null
                    ? List.of() : tc.preconditions();

            List<String> steps = tc.steps() == null ? List.of()
                    : tc.steps().stream().filter(s -> !isBlank(s)).toList();
            if (steps.isEmpty()) {
                log.warn("Тест-кейс {} пропущен: пустые steps", id);
                continue;
            }
            if (isBlank(tc.expectedResult())) {
                log.warn("Тест-кейс {} пропущен: пустой expectedResult", id);
                continue;
            }

            String priority = tc.priority();
            if (priority == null || !ALLOWED_PRIORITIES.contains(priority)) {
                log.warn("Некорректный priority '{}' для {} — заменён на P2", priority, id);
                priority = "P2";
            }

            repaired.add(new TestCase(id, type, title, requirementRef,
                    preconditions, steps, tc.expectedResult(), priority));
        }
        if (repaired.isEmpty() && !cases.isEmpty()) {
            throw new GenerationException("Все тест-кейсы ответа модели невалидны");
        }
        return repaired;
    }

    /**
     * Проверяет requirementRef: ссылка считается валидной, если она непустая, не длиннее 100 символов,
     * не содержит переносов строк и содержит хотя бы одну букву или цифру.
     *
     * @param ref значение requirementRef
     * @return {@code true}, если значение допустимо
     */
    private boolean isValidRequirementRef(String ref) {
        if (isBlank(ref)) return false;
        if (ref.length() > 100 || ref.contains("\n")) return false;
        return ref.chars().anyMatch(Character::isLetterOrDigit);
    }

    /**
     * Отбрасывает уточнения с пустым requirementRef или пустым question, пишет предупреждение в лог.
     * Тип уточнения не проверяется.
     *
     * @param clarifications уточнения из ответа модели
     * @return уточнения, прошедшие проверку
     */
    private List<Clarification> validateClarifications(List<Clarification> clarifications) {
        List<Clarification> valid = new ArrayList<>(clarifications.size());
        for (Clarification c : clarifications) {
            if (isBlank(c.requirementRef())) {
                log.warn("Уточнение пропущено: пустой requirementRef (вопрос: {})", c.question());
                continue;
            }
            if (isBlank(c.question())) {
                log.warn("Уточнение пропущено: пустой question для {}", c.requirementRef());
                continue;
            }
            valid.add(c);
        }
        return valid;
    }

    /**
     * Проверяет обоснованность тест-кейсов по документу и вырезает конкретику, которой в нём нет.
     * <p>
     * Для каждого тест-кейса выделяется блок требования из Markdown (см. {@link #extractRequirementBlock}).
     * Токены (HTTP-коды, snake_case-поля, числа от трёх цифр и числа с пробелами, query-параметры вне
     * абсолютных URL, цитаты UI) проверяются по документу, блоку требования и ответам пользователя.
     * Сами абсолютные URL не проверяются и не вырезаются, но их query-параметры проверяются и вырезаются
     * так же, как параметры вне URL. Неподтверждённые токены заменяются
     * нейтральными формулировками (см. {@link #sanitizeText}). В шагах NEGATIVE-тест-кейсов входные значения
     * (числа, значения query-параметров) не вырезаются: это тестовые данные. URL-параметры, имена полей и HTTP-коды
     * в них проверяются так же, как везде.
     * <p>
     * Если что-то вырезано и ответов пользователя нет, по каждому {@code requirementRef} добавляется одно
     * уточнение типа {@code UNGROUNDED_ASSUMPTION}. Если документа нет, тест-кейсы возвращаются без изменений.
     *
     * @param cases            тест-кейсы после {@link #validateAndRepairTestCases}
     * @param sourceMarkdown   исходный документ
     * @param groundingAnswers JSON ответов пользователя или {@code null}
     * @param outClarifications список, в который добавляются санитайзерные уточнения
     * @return санитизированные тест-кейсы
     */
    private List<TestCase> sanitizeAgainstDocument(
            List<TestCase> cases,
            String sourceMarkdown,
            String groundingAnswers,
            List<Clarification> outClarifications) {

        boolean answersProvided = groundingAnswers != null && !groundingAnswers.isBlank();
        if (sourceMarkdown == null || sourceMarkdown.isBlank()) {
            return cases;
        }
        String docLower = sourceMarkdown.toLowerCase(Locale.ROOT);
        String answersLower = answersProvided
                ? groundingAnswers.toLowerCase(Locale.ROOT)
                : null;
        Set<String> clarificationRefsAdded = new HashSet<>();
        List<TestCase> result = new ArrayList<>(cases.size());

        for (TestCase tc : cases) {
            String ref = nullSafe(tc.requirementRef());
            String requirementText = extractRequirementBlock(sourceMarkdown, ref);
            String refLower = requirementText == null
                    ? null
                    : requirementText.toLowerCase(Locale.ROOT);

            boolean isNegative = "NEGATIVE".equalsIgnoreCase(nullSafe(tc.type()));

            Set<String> ungrounded = new LinkedHashSet<>();

            String sanitizedExpected = sanitizeText(
                    nullSafe(tc.expectedResult()), docLower, refLower, answersLower,
                    ungrounded, false, isNegative, tc.type());
            if (sanitizedExpected.isBlank()) {
                sanitizedExpected = NEUTRAL_EXPECTED_RESULT;
            }

            List<String> sanitizedSteps = new ArrayList<>();
            if (tc.steps() != null) {
                for (String step : tc.steps()) {
                    String sanitized = sanitizeText(nullSafe(step), docLower, refLower, answersLower,
                            ungrounded, true, isNegative, tc.type());
                    if (sanitized.isBlank()) {
                        sanitized = NEUTRAL_STEP_SUFFIX.trim();
                    }
                    sanitizedSteps.add(sanitized);
                }
            }

            if (!ungrounded.isEmpty()) {
                log.warn("Тест-кейс {}: вырезаны неподтверждённые утверждения {} (требование {})",
                        tc.id(), ungrounded, ref);
                if (!answersProvided && clarificationRefsAdded.add(ref)) {
                    outClarifications.add(new Clarification(
                            ref,
                            "Уточните ожидаемое поведение: в документе нет утверждений "
                                    + String.join(", ", ungrounded)
                                    + ", но они были указаны в тест-кейсе",
                            "Модель домыслила конкретику, отсутствующую в требовании "
                                    + ref + "; тест-кейс санитизирован",
                            ClarificationType.UNGROUNDED_ASSUMPTION));
                }
            }

            result.add(new TestCase(
                    tc.id(), tc.type(), tc.title(), tc.requirementRef(),
                    tc.preconditions(), sanitizedSteps, sanitizedExpected, tc.priority()));
        }
        return result;
    }

    /**
     * Санитизирует один текст (ожидаемый результат или шаг) по всем правилам обоснованности.
     * <p>
     * Порядок: URL заменяются плейсхолдерами, чтобы их не затронули нормализация запятых и числа
     * (перед этим из них вырезаются query-параметры с необоснованными значениями);
     * вырезаются query-параметры с необоснованными значениями (вне URL); проверяются HTTP-коды (необоснованный код
     * заменяется на {@link #NEUTRAL_HTTP_CODE}), snake_case-поля, числа от трёх цифр (с заменой на
     * {@link #NEUTRAL_NUMBER}) и числа с пробелами; для типа UI проверяются
     * цитаты в «…» и "…"; URL восстанавливаются; если накопитель ungrounded непуст, нормализуются пробелы и пунктуация вне URL.
     * Найденные необоснованные фрагменты добавляются в {@code ungrounded}.
     * <p>
     * Входные значения шага NEGATIVE-кейса ({@code isStep && isNegative}) не проверяются: числа и значения
     * query-параметров считаются тестовыми данными. Имена query-параметров, поля, HTTP-коды и цитаты проверяются.
     *
     * @param text        исходный текст
     * @param docLower    документ в нижнем регистре
     * @param refLower    блок требования в нижнем регистре или {@code null}
     * @param answersLower ответы пользователя в нижнем регистре или {@code null}
     * @param ungrounded  накопитель описаний вырезанного; изменяется
     * @param isStep      {@code true} для шага, {@code false} для ожидаемого результата
     * @param isNegative  {@code true} для NEGATIVE-кейса
     * @param type        тип тест-кейса; при {@code UI} включается проверка цитат
     * @return санитизированный текст; может быть пустым, если всё вырезано
     */
    private String sanitizeText(String text, String docLower, String refLower,
                                String answersLower, Set<String> ungrounded,
                                boolean isStep, boolean isNegative, String type) {
        if (text.isBlank()) {
            return text;
        }
        String out = text;
        boolean inputValues = isStep && isNegative;

        List<String> protectedUrls = new ArrayList<>();
        Matcher urlM = URL_IN_TEXT.matcher(out);
        StringBuilder withPlaceholders = new StringBuilder();
        int last = 0;
        while (urlM.find()) {
            withPlaceholders.append(out, last, urlM.start());
            withPlaceholders.append("__URL_").append(protectedUrls.size()).append("__");
            protectedUrls.add(stripUngroundedQueryParams(urlM.group(), docLower, refLower,
                    answersLower, ungrounded, inputValues));
            last = urlM.end();
        }
        withPlaceholders.append(out.substring(last));
        String working = withPlaceholders.toString();

        working = stripUngroundedQueryParams(working, docLower, refLower, answersLower, ungrounded, inputValues);

        // Белого списка кодов нет: HTTP-код обоснован только документом, блоком требования или ответами
        working = replaceUngrounded(working, CONCRETE_HTTP_CODE, 1, docLower, refLower, answersLower,
                ungrounded,
                token -> "HTTP " + token,
                token -> false,
                NEUTRAL_HTTP_CODE);

        working = replaceUngrounded(working, CONCRETE_FIELD_NAME, 1, docLower, refLower, answersLower,
                ungrounded,
                token -> "поле '" + token + "'",
                FIELD_NAME_WHITELIST::contains);

        if (!inputValues) {
            working = replaceUngrounded(working, CONCRETE_NUMBER_LIMIT, 1, docLower, refLower, answersLower,
                    ungrounded,
                    token -> "число " + token,
                    token -> isArithmeticDerivative(token, docLower, refLower, answersLower),
                    NEUTRAL_NUMBER);

            working = replaceUngroundedNormalized(working, SPACED_NUMBER, docLower, refLower, answersLower,
                    ungrounded, token -> "число " + token, NEUTRAL_NUMBER);
        }

        if ("UI".equalsIgnoreCase(nullSafe(type))) {
            working = sanitizeUiQuotes(working, docLower, refLower, answersLower, ungrounded);
        }

        for (int i = 0; i < protectedUrls.size(); i++) {
            working = working.replace("__URL_" + i + "__", protectedUrls.get(i));
        }

        if (!ungrounded.isEmpty()) {
            working = normalizeSpacesOnlyOutsideUrls(working);
        }
        return working;
    }

    /**
     * Вырезает целиком query-параметры {@code name=value}, значение которых не обосновано.
     * <p>
     * Обоснование: значение встречается как отдельное слово в документе, блоке требования или ответах.
     * Пустые значения пропускаются. Метод применяется и к каждому абсолютному URL до замены его плейсхолдером
     * (параметры проверяются так же, как вне URL). В {@code ungrounded} добавляется описание вида
     * {@code параметр name=value}. Так убирается запрос вида {@code ?models=unknown}, а не
     * обрывок {@code ?models=}; если удалён первый параметр, следующий получает {@code ?}, а не {@code &}.
     * <p>
     * При {@code valuesAreInput = true} (входные значения шага NEGATIVE-кейса) проверяется только имя параметра:
     * значение считается тестовыми данными и не вырезается.
     *
     * @param text         текст (фрагмент текста или абсолютный URL)
     * @param docLower     документ в нижнем регистре
     * @param refLower     блок требования в нижнем регистре или {@code null}
     * @param answersLower ответы в нижнем регистре или {@code null}
     * @param ungrounded   накопитель описаний вырезанного; изменяется
     * @param valuesAreInput {@code true}, если значения параметров — входные данные теста
     * @return текст без необоснованных параметров
     */
    private String stripUngroundedQueryParams(String text, String docLower, String refLower,
                                              String answersLower, Set<String> ungrounded,
                                              boolean valuesAreInput) {
        Matcher m = QUERY_PARAM.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean changed = false;
        while (m.find()) {
            String name = m.group(1);
            String value = m.group(2);
            if (value == null || value.isBlank()) {
                continue;
            }
            boolean grounded = valuesAreInput
                    ? isGroundedToken(name.substring(1), docLower, refLower, answersLower)
                    : isGroundedToken(value, docLower, refLower, answersLower);
            if (grounded) {
                continue;
            }
            ungrounded.add("параметр " + name.substring(1) + "=" + value);
            sb.append(text, last, m.start());
            int next = m.end();
            if (name.startsWith("?") && next < text.length() && text.charAt(next) == '&'
                    && QUERY_PARAM_NEXT.matcher(text.substring(next + 1)).lookingAt()) {
                // Первый параметр удалён, следующий становится первым: "?a=1&b=2" -> "?b=2"
                sb.append(stripUngroundedQueryParams("?" + text.substring(next + 1),
                        docLower, refLower, answersLower, ungrounded, valuesAreInput));
                return sb.toString();
            }
            last = next;
            changed = true;
        }
        if (!changed) return text;
        sb.append(text.substring(last));
        return sb.toString();
    }

    /**
     * Проверяет, обоснован ли числовой токен производными величинами.
     * <p>
     * Число обосновано, если оно есть в источниках или может быть получено умножением на 24, 7 или 60
     * из числа, которое есть в источниках. Например, 168 = 7 × 24 обосновано, если в документе есть 7.
     * Числа меньше или равные нулю при делении не учитываются.
     *
     * @param token       число в виде строки
     * @param docLower    документ в нижнем регистре
     * @param refLower    блок требования в нижнем регистре или {@code null}
     * @param answersLower ответы в нижнем регистре или {@code null}
     * @return {@code true}, если число обосновано прямо или через производную
     */
    private boolean isArithmeticDerivative(String token, String docLower, String refLower,
                                           String answersLower) {
        if (containsToken(docLower, token)
                || containsToken(refLower, token)
                || (answersLower != null && containsToken(answersLower, token))) {
            return true;
        }
        try {
            long n = Long.parseLong(token);
            for (long base : new long[]{n / 24, n / 7, n / 60}) {
                if (base <= 0) continue;
                if (base * 24 == n || base * 7 == n || base * 60 == n) {
                    String b = Long.toString(base);
                    if (containsToken(docLower, b)
                            || containsToken(refLower, b)
                            || (answersLower != null && containsToken(answersLower, b))) {
                        return true;
                    }
                }
            }
        } catch (NumberFormatException ignored) {
        }
        return false;
    }

    /**
     * Проверяет цитаты в UI-тест-кейсах. Цитата в «…» или "…" длиной 2–200 символов остаётся, если
     * она (в нижнем регистре) есть в документе, блоке требования или ответах. Иначе она заменяется на «…».
     * Каждая заменённая цитата добавляется в {@code ungrounded} как {@code цитата «…»}.
     *
     * @param text        текст тест-кейса
     * @param docLower    документ в нижнем регистре
     * @param refLower    блок требования в нижнем регистре или {@code null}
     * @param answersLower ответы в нижнем регистре или {@code null}
     * @param ungrounded  накопитель описаний вырезанного; изменяется
     * @return текст с проверенными цитатами
     */
    private String sanitizeUiQuotes(String text, String docLower, String refLower,
                                    String answersLower, Set<String> ungrounded) {
        Pattern quote = Pattern.compile("[«\"]([^»\"]{2,200})[»\"]");
        Matcher m = quote.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            String quoted = m.group(1);
            String qLower = quoted.toLowerCase(Locale.ROOT);
            boolean grounded = docLower.contains(qLower)
                    || (refLower != null && refLower.contains(qLower))
                    || (answersLower != null && answersLower.contains(qLower));
            if (grounded) {
                sb.append(m.group());
            } else {
                ungrounded.add("цитата «" + quoted + "»");
                sb.append("«…»");
            }
            last = m.end();
        }
        sb.append(text.substring(last));
        return sb.toString();
    }

    /**
     * Проверяет, что токен обоснован: встречается как отдельное слово в документе, блоке требования или ответах
     * пользователя. Тексты сравниваются в нижнем регистре; {@code token} может быть в любом регистре.
     *
     * @param token        токен
     * @param docLower     документ в нижнем регистре
     * @param refLower     блок требования в нижнем регистре или {@code null}
     * @param answersLower ответы в нижнем регистре или {@code null}
     * @return {@code true}, если токен есть в одном из источников
     */
    private boolean isGroundedToken(String token, String docLower, String refLower, String answersLower) {
        String lower = token.toLowerCase(Locale.ROOT);
        return containsToken(docLower, lower)
                || containsToken(refLower, lower)
                || (answersLower != null && containsToken(answersLower, lower));
    }

    /**
     * Упрощённая версия {@link #replaceUngrounded(String, Pattern, int, String, String, String, Set,
     * java.util.function.Function, java.util.function.Predicate, String)} с пустой заменой: необоснованные
     * совпадения удаляются.
     *
     * @param text        текст
     * @param pattern     шаблон для поиска
     * @param group       номер группы с токеном
     * @param docLower    документ в нижнем регистре
     * @param refLower    блок требования в нижнем регистре или {@code null}
     * @param answersLower ответы в нижнем регистре или {@code null}
     * @param ungrounded  накопитель описаний; изменяется
     * @param description описание токена для отчёта
     * @param whitelist   проверка токена без обращения к документу
     * @return текст без необоснованных совпадений
     */
    private String replaceUngrounded(
            String text, Pattern pattern, int group, String docLower, String refLower,
            String answersLower, Set<String> ungrounded,
            java.util.function.Function<String, String> description,
            java.util.function.Predicate<String> whitelist) {
        return replaceUngrounded(text, pattern, group, docLower, refLower, answersLower,
                ungrounded, description, whitelist, "");
    }

    /**
     * Заменяет совпадения шаблона, которые не обоснованы.
     * <p>
     * Токен обоснован, если его принимает {@code whitelist} или он встречается как отдельное слово
     * в документе, блоке требования или ответах. Необоснованное совпадение заменяется на
     * {@code replacement} (пустая строка удаляет его), а его описание, построенное через
     * {@code description}, добавляется в {@code ungrounded}.
     *
     * @param text        текст
     * @param pattern     шаблон для поиска
     * @param group       номер группы с токеном
     * @param docLower    документ в нижнем регистре
     * @param refLower    блок требования в нижнем регистре или {@code null}
     * @param answersLower ответы в нижнем регистре или {@code null}
     * @param ungrounded  накопитель описаний; изменяется
     * @param description строит описание из токена
     * @param whitelist   проверка токена без обращения к документу
     * @param replacement текст замены для необоснованного совпадения
     * @return обработанный текст
     */
    private String replaceUngrounded(
            String text, Pattern pattern, int group, String docLower, String refLower,
            String answersLower, Set<String> ungrounded,
            java.util.function.Function<String, String> description,
            java.util.function.Predicate<String> whitelist,
            String replacement) {

        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean changed = false;
        while (m.find()) {
            String token = m.group(group);
            String tokenLower = token.toLowerCase(Locale.ROOT);
            boolean grounded = whitelist.test(token)
                    || containsToken(docLower, tokenLower)
                    || containsToken(refLower, tokenLower)
                    || (answersLower != null && containsToken(answersLower, tokenLower));
            if (grounded) {
                continue;
            }
            ungrounded.add(description.apply(token));
            sb.append(text, last, m.start());
            if (replacement != null && !replacement.isEmpty()) {
                sb.append(replacement);
            }
            last = m.end();
            changed = true;
        }
        if (!changed) {
            return text;
        }
        sb.append(text.substring(last));
        return sb.toString();
    }

    /**
     * Вариант {@link #replaceUngrounded} для чисел с пробелами или неразрывными пробелами.
     * Для проверки пробелы удаляются из совпадения, например {@code 10 000} проверяется как {@code 10000}.
     * Необоснованное совпадение заменяется целиком на {@code replacement}.
     *
     * @param text        текст
     * @param pattern     шаблон числа с пробелами
     * @param docLower    документ в нижнем регистре
     * @param refLower    блок требования в нижнем регистре или {@code null}
     * @param answersLower ответы в нижнем регистре или {@code null}
     * @param ungrounded  накопитель описаний; изменяется
     * @param description строит описание из совпадения
     * @param replacement текст замены
     * @return обработанный текст
     */
    private String replaceUngroundedNormalized(
            String text, Pattern pattern, String docLower, String refLower,
            String answersLower, Set<String> ungrounded,
            java.util.function.Function<String, String> description,
            String replacement) {

        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean changed = false;
        while (m.find()) {
            String token = m.group();
            String normalized = token.replaceAll("[\\s\\u00A0]", "");
            String normLower = normalized.toLowerCase(Locale.ROOT);
            boolean grounded = containsToken(docLower, normLower)
                    || containsToken(refLower, normLower)
                    || (answersLower != null && containsToken(answersLower, normLower));
            if (grounded) {
                continue;
            }
            ungrounded.add(description.apply(token));
            sb.append(text, last, m.start());
            if (replacement != null && !replacement.isEmpty()) {
                sb.append(replacement);
            }
            last = m.end();
            changed = true;
        }
        if (!changed) {
            return text;
        }
        sb.append(text.substring(last));
        return sb.toString();
    }

    /**
     * Нормализует пробелы и пунктуацию вне URL: одна запятая с пробелом после, без пробела перед
     * знаками {@code .,;:}, без повторных пробелов, без разделителей в начале и в конце каждого фрагмента вне URL.
     * Пробел, отделяющий текст от соседнего URL, сохраняется (один пробел на границе).
     * Фрагменты URL остаются без изменений.
     *
     * @param text текст с восстановленными URL
     * @return нормализованный текст без пробелов по краям
     */
    private String normalizeSpacesOnlyOutsideUrls(String text) {
        Matcher m = URL_IN_TEXT.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(normalizePlainFragment(text.substring(last, m.start()), last > 0, true));
            sb.append(m.group());
            last = m.end();
        }
        sb.append(normalizePlainFragment(text.substring(last), last > 0, false));
        return sb.toString().trim();
    }

    /**
     * Нормализует один фрагмент вне URL (правила см. в {@link #normalizeSpacesOnlyOutsideUrls}).
     * Если фрагмент примыкает к URL и у этой границы в исходном тексте был пробел или разделитель
     * {@code ,;:}, на границе остаётся один пробел, чтобы текст не склеивался с URL.
     *
     * @param plain     фрагмент вне URL
     * @param afterUrl  {@code true}, если перед фрагментом есть URL
     * @param beforeUrl {@code true}, если после фрагмента есть URL
     * @return нормализованный фрагмент
     */
    private String normalizePlainFragment(String plain, boolean afterUrl, boolean beforeUrl) {
        String core = plain.replaceAll("\\s*,\\s*", ", ")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("\\s+([.,;:])", "$1")
                .replaceAll("^[\\s,;:]+", "")
                .replaceAll("[\\s,;:]+$", "");
        boolean spaceBefore = afterUrl && !plain.isEmpty() && isUrlSeparator(plain.charAt(0));
        boolean spaceAfter = beforeUrl && !plain.isEmpty() && isUrlSeparator(plain.charAt(plain.length() - 1));
        if (core.isEmpty()) {
            return spaceBefore && spaceAfter ? " " : "";
        }
        return (spaceBefore ? " " : "") + core + (spaceAfter ? " " : "");
    }

    /**
     * Проверяет, что символ отделяет текст от URL: пробел или один из знаков {@code ,;:}.
     *
     * @param c символ на границе фрагмента и URL
     * @return {@code true}, если символ является пробелом или разделителем
     */
    private boolean isUrlSeparator(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || ",;:".indexOf(c) >= 0;
    }

    /**
     * Извлекает раздел документа, соответствующий требованию.
     * Раздел начинается с первого заголовка Markdown, в тексте которого есть {@code ref} как целый идентификатор
     * (за ним не идут буквы, цифры, подчёркивание или дефис), и заканчивается
     * перед следующим заголовком того же или более высокого уровня (меньшее число решёток).
     * Регистр учитывается, {@code ref} экранируется.
     *
     * @param markdown исходный документ
     * @param ref      ссылка на требование
     * @return текст раздела с переводами строк или {@code null}, если ref пустой, равен {@code N/A}
     *         или заголовка с ref нет
     */
    private String extractRequirementBlock(String markdown, String ref) {
        if (ref.isBlank() || "N/A".equalsIgnoreCase(ref)) {
            return null;
        }
        String[] lines = markdown.split("\n", -1);
        int startLine = -1;
        int headingLevel = -1;
        // После идентификатора не должно идти буквы, цифры, подчёркивания или дефиса: FR-UI-1 не совпадает с FR-UI-10
        Pattern headingWithRef = Pattern.compile(
                "^(#{1,6})\\s+.*" + Pattern.quote(ref) + "(?![\\p{L}\\p{N}_\\-]).*$");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = headingWithRef.matcher(lines[i]);
            if (m.find()) {
                startLine = i;
                headingLevel = m.group(1).length();
                break;
            }
        }
        if (startLine < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i < lines.length; i++) {
            if (i > startLine) {
                Matcher m = Pattern.compile("^(#{1,6})\\s").matcher(lines[i]);
                if (m.find() && m.group(1).length() <= headingLevel) {
                    break;
                }
            }
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    /**
     * Проверяет, что токен встречается в тексте как отдельное слово: слева и справа нет символов
     * {@code [a-z0-9_]}. Текст и токен должны быть в нижнем регистре.
     *
     * @param haystackLower текст в нижнем регистре; {@code null} даёт {@code false}
     * @param tokenLower    токен в нижнем регистре
     * @return {@code true}, если токен найден как отдельное слово
     */
    private boolean containsToken(String haystackLower, String tokenLower) {
        if (haystackLower == null) {
            return false;
        }
        Pattern p = Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(tokenLower) + "(?![a-z0-9_])");
        return p.matcher(haystackLower).find();
    }

    /**
     * Объединяет уточнения по {@code requirementRef}: на одно требование остаётся один вопрос.
     * <p>
     * Вызывается только для вопросов к пользователю ({@code MISSING_REQUIREMENT}); {@code UNGROUNDED_ASSUMPTION}
     * сюда не передаются и не сливаются. Основным считается первое уточнение типа {@code MISSING_REQUIREMENT},
     * а если его нет, то первое в группе. Его {@code reason} сохраняется. Если в группе несколько уточнений,
     * их вопросы добавляются в конец {@code reason} с префиксом «Дополнительные детали: ».
     * Порядок групп сохраняется.
     *
     * @param src уточнения до дедупликации
     * @return уточнения по одному на каждый {@code requirementRef}
     */
    private List<Clarification> dedupeClarifications(List<Clarification> src) {
        Map<String, List<Clarification>> byRef = new LinkedHashMap<>();
        for (Clarification c : src) {
            byRef.computeIfAbsent(c.requirementRef(), k -> new ArrayList<>()).add(c);
        }
        List<Clarification> out = new ArrayList<>(byRef.size());
        for (Map.Entry<String, List<Clarification>> e : byRef.entrySet()) {
            List<Clarification> group = e.getValue();
            Clarification primary = group.stream()
                    .filter(c -> c.type() == ClarificationType.MISSING_REQUIREMENT)
                    .findFirst()
                    .orElse(group.get(0));
            String details = primary.reason();
            if (group.size() > 1) {
                String extra = group.stream()
                        .filter(c -> c != primary)
                        .map(Clarification::question)
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("");
                details = isBlank(details)
                        ? "Дополнительные детали: " + extra
                        : details + "; Дополнительные детали: " + extra;
            }
            out.add(new Clarification(e.getKey(), primary.question(), details, primary.type()));
        }
        return out;
    }

    /**
     * Формирует подсказку для модели: перечень URL, HTTP-методов, HTTP-кодов, полей и чисел, найденных
     * в документе. Числа дополняются производными (×24, ×7, ×60). Для пустого документа возвращается
     * строка {@code (пусто)}.
     *
     * @param markdown исходный документ
     * @return текст подсказки, который добавляется в пользовательский промпт
     */
    private String extractAllowedTokens(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "(пусто)";
        }
        Set<String> urls = new TreeSet<>();
        Set<String> methods = new TreeSet<>();
        Set<String> httpCodes = new TreeSet<>();
        Set<String> fields = new TreeSet<>();
        Set<String> numbers = new TreeSet<>();

        // Перед «/» не должна стоять буква или цифра: иначе «and/or» и «24/7» дают ложные пути
        Matcher m = Pattern.compile("(?<![A-Za-z0-9])(/[a-zA-Z0-9_\\-./{}]+)").matcher(markdown);
        while (m.find()) {
            String u = m.group(1);
            if (u.length() > 1 && !u.endsWith("//")) urls.add(u);
        }
        m = ABSOLUTE_URL.matcher(markdown);
        while (m.find()) urls.add(m.group());

        m = HTTP_METHOD.matcher(markdown);
        while (m.find()) methods.add(m.group(1));
        // Все коды, которые есть в документе: без белого списка, иначе модель получила бы коды без связи с ним
        m = CONCRETE_HTTP_CODE.matcher(markdown);
        while (m.find()) {
            httpCodes.add(m.group(1));
        }
        m = CONCRETE_FIELD_NAME.matcher(markdown);
        while (m.find()) fields.add(m.group(1));
        m = CONCRETE_NUMBER_LIMIT.matcher(markdown);
        while (m.find()) numbers.add(m.group(1));
        m = SPACED_NUMBER.matcher(markdown);
        while (m.find()) {
            numbers.add(m.group().replaceAll("[\\s\\u00A0]", ""));
        }

        Set<String> derived = new TreeSet<>();
        for (String n : new ArrayList<>(numbers)) {
            try {
                long v = Long.parseLong(n);
                derived.add(Long.toString(v * 24));
                derived.add(Long.toString(v * 7));
                derived.add(Long.toString(v * 60));
            } catch (NumberFormatException ignored) {
            }
        }
        numbers.addAll(derived);

        StringBuilder sb = new StringBuilder();
        sb.append("- URL: ").append(urls.isEmpty() ? "(нет)" : urls).append('\n');
        sb.append("- HTTP-методы: ").append(methods.isEmpty() ? "(нет)" : methods).append('\n');
        sb.append("- HTTP-коды: ").append(httpCodes.isEmpty() ? "(нет)" : httpCodes).append('\n');
        sb.append("- Имена полей: ").append(fields.isEmpty() ? "(нет)" : fields).append('\n');
        sb.append("- Числа: ").append(numbers.isEmpty() ? "(нет)" : numbers).append('\n');
        sb.append("Используй эти значения для конкретики (URL, коды, имена полей, лимиты). ")
                .append("Для UI-проверок разрешено цитировать ТЕКСТ из документа (заголовки, подписи, ")
                .append("сообщения) — это не домысел, а цитата. Покрывай ВСЕ разделы документа ")
                .append("(UI, API, NEGATIVE, E2E), а не только API.");
        return sb.toString();
    }

    /**
     * Возвращает тип, допустимый для построения id: неизвестный или {@code null} тип заменяется на {@code UI}.
     *
     * @param type тип из ответа модели
     * @return значение из {@link #ALLOWED_TYPES}
     */
    private String safeType(String type) {
        return type == null || !ALLOWED_TYPES.contains(type) ? "UI" : type;
    }

    /**
     * Сокращение типа для id: {@code NEGATIVE → NEG}, {@code SECURITY → SEC}, {@code PERFORMANCE → PERF};
     * остальные типы не меняются.
     *
     * @param type допустимый тип из {@link #ALLOWED_TYPES}
     * @return сокращение для id
     */
    private String typeShort(String type) {
        return switch (type) {
            case "NEGATIVE" -> "NEG";
            case "SECURITY" -> "SEC";
            case "PERFORMANCE" -> "PERF";
            default -> type;
        };
    }

    /**
     * Проверяет, что строка {@code null} или состоит только из пробелов.
     *
     * @param s строка
     * @return {@code true}, если строка пустая или состоит только из пробелов
     */
    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Заменяет {@code null} на пустую строку.
     *
     * @param s строка
     * @return {@code s} или пустая строка
     */
    private String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
