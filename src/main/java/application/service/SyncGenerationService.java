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

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncGenerationService {

    private static final Set<String> ALLOWED_TYPES =
            Set.of("UI", "API", "E2E", "NEGATIVE", "SECURITY", "PERFORMANCE");
    private static final Set<String> ALLOWED_PRIORITIES =
            Set.of("P0", "P1", "P2", "P3");
    private static final Pattern ID_PATTERN =
            Pattern.compile("^TC-(UI|API|E2E|NEG|SEC|PERF)-\\d{3,}$");

    /**
     * HTTP-коды. Расширенный whitelist: 1xx–5xx «общие» коды считаются нормальной
     * конкретикой, если рядом есть слово http/статус/ошибка/ответ — см. isGroundedHttpCode.
     */
    private static final Pattern CONCRETE_HTTP_CODE =
            Pattern.compile("\\b([1-5]\\d{2})\\b");
    private static final Set<String> HTTP_CODE_WHITELIST =
            Set.of("200", "201", "204", "400", "401", "403", "404", "409", "422",
                    "429", "500", "502", "503");

    /**
     * Имена полей API (snake_case). Проверяем только известные «опасные» имена,
     * которые модель любит домысливать. Остальное не трогаем — иначе ловим
     * служебные поля вроде utc_offset_seconds, snake_case и т.п.
     */
    private static final Pattern CONCRETE_FIELD_NAME =
            Pattern.compile("\\b([a-z][a-z0-9]*_[a-z0-9_]+)\\b");
    private static final Set<String> FIELD_NAME_WHITELIST = Set.of(
            // служебные поля ответа, которые допустимы всегда
            "utc_offset_seconds", "snake_case", "requirement_ref",
            // общие имена, которые не являются «домыслом» сами по себе
            "error_message", "error_code", "request_id", "response_time"
    );

    /**
     * Числовые лимиты. Сузили до 3+ цифр: одиночные/двузначные числа
     * (52, 13, 41, 17, 16) больше не считаются домыслом — это либо координаты-примеры,
     * либо границы диапазонов, которые модель вправе назвать нейтрально.
     */
    private static final Pattern CONCRETE_NUMBER_LIMIT =
            Pattern.compile("\\b(\\d{3,})\\b");

    private static final String NEUTRAL_EXPECTED_RESULT =
            "Поведение соответствует требованиям (см. уточнение)";
    private static final String NEUTRAL_STEP_SUFFIX =
            "(точное поведение уточняется)";

    private final OpenRouterClient openRouter;
    private final ObjectMapper mapper;
    private final AppProperties appProps;

    public record GenerationResult(List<TestCase> testCases, List<Clarification> clarifications) {
    }

    public GenerationResult generate(String markdown) {
        String prompt = appProps.getPrompts().getGenerate()
                .replace("{{schema}}", appProps.getPrompts().getJsonSchema());
        // Явный whitelist разрешённых конкретных токенов, извлечённых из документа.
        String userText = markdown + "\n\n## Разрешённые конкретные значения (из документа)\n"
                + extractAllowedTokens(markdown);
        String response = openRouter.complete(prompt, userText);
        return parseResponse(response, markdown, null);
    }

    /**
     * Ответы пользователя — источник истины. Санитизация по документу здесь
     * НЕ применяется (иначе ответ «forecast_days 1..16» снова вырежется и снова
     * породит clarification → бесконечный цикл).
     */
    public GenerationResult answer(String markdown, String existingJson, String answersJson) {
        String prompt = appProps.getPrompts().getAnswers()
                .replace("{{schema}}", appProps.getPrompts().getJsonSchema());
        String userText = "## Документ\n" + markdown
                + "\n\n## Ранее сгенерированные тест-кейсы\n" + existingJson
                + "\n\n## Ответы на уточнения\n" + answersJson;
        String response = openRouter.complete(prompt, userText);
        // skipSanitize=true: после ответов пользователя grounding-источник — ответы, а не документ.
        return parseResponse(response, markdown, answersJson);
    }

    /**
     * @param groundingAnswers JSON с ответами пользователя; если не null — используется
     *                         как дополнительный источник «заземления» и отключает
     *                         генерацию UNGROUNDED_ASSUMPTION (это не вопрос пользователю).
     */
    private GenerationResult parseResponse(String json, String sourceMarkdown, String groundingAnswers) {
        try {
            String cleaned = json.replaceAll("^```json\\s*", "")
                    .replaceAll("```\\s*$", "")
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
            List<Clarification> modelClarifications = mapper.convertValue(
                    map.getOrDefault("clarifications", List.of()),
                    new TypeReference<List<Clarification>>() {
                    });

            // 1) Структурная валидация: жёсткая (502) + мягкая (исправляем).
            List<TestCase> validated = validateAndRepairTestCases(rawCases);
            validateClarifications(modelClarifications);

            // 2) Санитизация домыслов. При наличии ответов пользователя
            //    UNGROUNDED_ASSUMPTION не генерируем — это не вопрос пользователю.
            List<Clarification> groundingClarifications = new ArrayList<>();
            List<TestCase> sanitized = sanitizeAgainstDocument(
                    validated, sourceMarkdown, groundingAnswers, groundingClarifications);

            // 3) Объединяем: модельные + grounding. Дедуп по requirementRef,
            //    приоритет у MISSING_REQUIREMENT, и по одному вопросу на ref.
            List<Clarification> merged = new ArrayList<>(modelClarifications);
            merged.addAll(groundingClarifications);

            return new GenerationResult(sanitized, dedupeClarifications(merged));
        } catch (GenerationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Ошибка парсинга JSON: {}", json, e);
            throw new GenerationException("Модель вернула невалидный JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Мягкая валидация: восстанавливаем то, что можно восстановить, вместо 502.
     * 502 остаётся только для реально сломанного ответа (нет steps/expectedResult/id).
     */
    private List<TestCase> validateAndRepairTestCases(List<TestCase> cases) {
        Set<String> seenIds = new HashSet<>();
        List<TestCase> repaired = new ArrayList<>(cases.size());
        int autoSeq = 1;

        for (TestCase tc : cases) {
            // id: если битый — перенумеровываем
            String id = tc.id();
            if (isBlank(id) || !ID_PATTERN.matcher(id).matches()) {
                String type = safeType(tc.type());
                id = "TC-" + typeShort(type) + "-" + String.format("%03d", autoSeq++);
                log.warn("Некорректный id '{}' заменён на '{}'", tc.id(), id);
            }
            if (!seenIds.add(id)) {
                String type = safeType(tc.type());
                String newId;
                do {
                    newId = "TC-" + typeShort(type) + "-" + String.format("%03d", autoSeq++);
                } while (!seenIds.add(newId));
                log.warn("Дубликат id '{}' заменён на '{}'", id, newId);
                id = newId;
            }

            // type: если не из списка — NEGATIVE-дефолт не годится, ставим UI и логируем
            String type = tc.type();
            if (isBlank(type) || !ALLOWED_TYPES.contains(type)) {
                log.warn("Недопустимый type '{}' для {} — заменён на UI", type, id);
                type = "UI";
            }

            // title: обрезаем до 200
            String title = nullSafe(tc.title());
            if (title.isBlank()) {
                throw new GenerationException("Некорректный title для " + id);
            }
            if (title.length() > 200) {
                title = title.substring(0, 200).trim();
            }

            if (isBlank(tc.requirementRef())) {
                throw new GenerationException("requirementRef пуст для " + id);
            }
            if (tc.preconditions() == null) {
                // preconditions может быть пустым массивом, но не null
                tc = new TestCase(tc.id(), tc.type(), tc.title(), tc.requirementRef(),
                        List.of(), tc.steps(), tc.expectedResult(), tc.priority());
            }
            if (tc.steps() == null || tc.steps().isEmpty()) {
                throw new GenerationException("steps пуст для " + id);
            }
            if (isBlank(tc.expectedResult())) {
                throw new GenerationException("expectedResult пуст для " + id);
            }

            // priority: если пустой/битый — P2 (мягкая починка)
            String priority = tc.priority();
            if (priority == null || !ALLOWED_PRIORITIES.contains(priority)) {
                log.warn("Некорректный priority '{}' для {} — заменён на P2", priority, id);
                priority = "P2";
            }

            repaired.add(new TestCase(id, type, title, tc.requirementRef(),
                    tc.preconditions(), tc.steps(), tc.expectedResult(), priority));
        }
        return repaired;
    }

    private void validateClarifications(List<Clarification> clarifications) {
        for (Clarification c : clarifications) {
            if (isBlank(c.requirementRef())) {
                throw new GenerationException("Пустой requirementRef в clarification");
            }
            if (isBlank(c.question())) {
                throw new GenerationException("Пустой question в clarification для " + c.requirementRef());
            }
        }
    }

    /**
     * Санитизация «опоры на документ». НЕ бросает исключение.
     * <p>
     * Важно: при наличии {@code groundingAnswers} (ответы пользователя) —
     * UNGROUNDED_ASSUMPTION не генерируется, потому что источник истины
     * теперь ответы пользователя, а не документ.
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

            Set<String> ungrounded = new LinkedHashSet<>();

            String sanitizedExpected = sanitizeText(
                    nullSafe(tc.expectedResult()), docLower, refLower, answersLower, ungrounded);
            if (sanitizedExpected.isBlank()) {
                sanitizedExpected = NEUTRAL_EXPECTED_RESULT;
            }

            List<String> sanitizedSteps = new ArrayList<>();
            if (tc.steps() != null) {
                for (String step : tc.steps()) {
                    String sanitized = sanitizeText(
                            nullSafe(step), docLower, refLower, answersLower, ungrounded);
                    if (sanitized.isBlank()) {
                        sanitized = NEUTRAL_STEP_SUFFIX.trim();
                    }
                    sanitizedSteps.add(sanitized);
                }
            }

            if (!ungrounded.isEmpty()) {
                log.warn("Тест-кейс {}: вырезаны неподтверждённые утверждения {} (требование {})",
                        tc.id(), ungrounded, ref);
                // UNGROUNDED_ASSUMPTION — только когда нет ответов пользователя.
                // Это не вопрос пользователю, а сигнал о качестве генерации.
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
                    tc.id(),
                    tc.type(),
                    tc.title(),
                    tc.requirementRef(),
                    tc.preconditions(),
                    sanitizedSteps,
                    sanitizedExpected,
                    tc.priority()
            ));
        }
        return result;
    }

    private String sanitizeText(String text, String docLower, String refLower,
                                String answersLower, Set<String> ungrounded) {
        if (text.isBlank()) {
            return text;
        }
        String out = text;

        // HTTP-коды: whitelist общих кодов + whitelist по документу/ответам/требованию.
        out = replaceUngrounded(out, CONCRETE_HTTP_CODE, 1, docLower, refLower, answersLower,
                ungrounded,
                token -> "HTTP " + token,
                token -> HTTP_CODE_WHITELIST.contains(token) || isGroundedHttpContext(text, token));

        // snake_case-поля: только «опасные», которые не в whitelist и не заземлены.
        out = replaceUngrounded(out, CONCRETE_FIELD_NAME, 1, docLower, refLower, answersLower,
                ungrounded,
                token -> "поле '" + token + "'",
                FIELD_NAME_WHITELIST::contains);

        // Числовые лимиты: только 3+ цифр (52/13/41/8601 больше не домысел).
        out = replaceUngrounded(out, CONCRETE_NUMBER_LIMIT, 1, docLower, refLower, answersLower,
                ungrounded,
                token -> "число " + token,
                token -> false);

        out = out.replaceAll("\\s*,\\s*", ", ")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("\\s+([.,;:])", "$1")
                .replaceAll("^[\\s,;:]+", "")
                .replaceAll("[\\s,;:]+$", "")
                .trim();
        return out;
    }

    /**
     * HTTP-код считается «заземлённым», если рядом (в пределах 20 символов) есть
     * слово http/статус/status/ошибк/error/ответ/response. Это устраняет вырезание
     * «HTTP 200» из фразы «успешный ответ».
     */
    private boolean isGroundedHttpContext(String text, String code) {
        Pattern p = Pattern.compile(
                "(?i)(?:http|статус|status|ошибк|error|ответ|response).{0,20}\\b"
                        + Pattern.quote(code) + "\\b|\\b" + Pattern.quote(code)
                        + "\\b.{0,20}(?:http|статус|status|ошибк|error|ответ|response)");
        return p.matcher(text).find();
    }

    private String replaceUngrounded(
            String text,
            Pattern pattern,
            int group,
            String docLower,
            String refLower,
            String answersLower,
            Set<String> ungrounded,
            java.util.function.Function<String, String> description,
            java.util.function.Predicate<String> whitelist) {

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
     * Извлекает из Markdown блок требования по его ID (заголовок с ref до следующего
     * заголовка того же/более высокого уровня). Возвращает null, если не нашли.
     */
    private String extractRequirementBlock(String markdown, String ref) {
        if (ref.isBlank() || "N/A".equalsIgnoreCase(ref)) {
            return null;
        }
        String[] lines = markdown.split("\n", -1);
        int startLine = -1;
        int headingLevel = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(ref)) {
                Matcher m = Pattern.compile("^(#{1,6})\\s").matcher(lines[i]);
                if (m.find()) {
                    startLine = i;
                    headingLevel = m.group(1).length();
                    break;
                }
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

    private boolean containsToken(String haystackLower, String tokenLower) {
        if (haystackLower == null) {
            return false;
        }
        Pattern p = Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(tokenLower) + "(?![a-z0-9_])");
        return p.matcher(haystackLower).find();
    }

    /**
     * Дедуп: один requirementRef — один вопрос. Приоритет MISSING_REQUIREMENT
     * над UNGROUNDED_ASSUMPTION. Тексты не склеиваются через " / " — берём
     * наиболее полный вариант; остальные сохраняются в reason как детализация.
     */
    private List<Clarification> dedupeClarifications(List<Clarification> src) {
        Map<String, List<Clarification>> byRef = new LinkedHashMap<>();
        for (Clarification c : src) {
            byRef.computeIfAbsent(c.requirementRef(), k -> new ArrayList<>()).add(c);
        }
        List<Clarification> out = new ArrayList<>(byRef.size());
        for (Map.Entry<String, List<Clarification>> e : byRef.entrySet()) {
            List<Clarification> group = e.getValue();
            // MISSING_REQUIREMENT важнее
            Clarification primary = group.stream()
                    .filter(c -> c.type() == ClarificationType.MISSING_REQUIREMENT)
                    .findFirst()
                    .orElse(group.get(0));
            String details = group.size() > 1
                    ? "Дополнительные детали: " + group.stream()
                    .filter(c -> c != primary)
                    .map(Clarification::question)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("")
                    : primary.reason();
            out.add(new Clarification(
                    e.getKey(),
                    primary.question(),
                    details,
                    primary.type()
            ));
        }
        return out;
    }

    /**
     * Извлекает из документа конкретные токены (HTTP-коды, имена полей, числа),
     * чтобы передать их модели в user-промпт как «разрешённые».
     */
    private String extractAllowedTokens(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "(пусто)";
        }
        Set<String> httpCodes = new TreeSet<>();
        Set<String> fields = new TreeSet<>();
        Set<String> numbers = new TreeSet<>();

        Matcher m = CONCRETE_HTTP_CODE.matcher(markdown);
        while (m.find()) {
            if (HTTP_CODE_WHITELIST.contains(m.group(1))) httpCodes.add(m.group(1));
        }
        m = CONCRETE_FIELD_NAME.matcher(markdown);
        while (m.find()) fields.add(m.group(1));
        m = CONCRETE_NUMBER_LIMIT.matcher(markdown);
        while (m.find()) numbers.add(m.group(1));

        StringBuilder sb = new StringBuilder();
        sb.append("- HTTP-коды: ").append(httpCodes.isEmpty() ? "(нет)" : httpCodes).append('\n');
        sb.append("- Имена полей: ").append(fields.isEmpty() ? "(нет)" : fields).append('\n');
        sb.append("- Числа: ").append(numbers.isEmpty() ? "(нет)" : numbers).append('\n');
        sb.append("Используй ТОЛЬКО эти конкретные значения. Всё остальное — clarification.");
        return sb.toString();
    }

    private String safeType(String type) {
        return type == null || !ALLOWED_TYPES.contains(type) ? "UI" : type;
    }

    private String typeShort(String type) {
        return switch (type) {
            case "NEGATIVE" -> "NEG";
            case "SECURITY" -> "SEC";
            case "PERFORMANCE" -> "PERF";
            default -> type;
        };
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
