package application.service;

import application.config.AppProperties;
import application.dto.Dtos.Clarification;
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
     * Конкретика, которую модель часто «домысливает», если её нет в документе.
     */
    private static final Pattern CONCRETE_HTTP_CODE =
            Pattern.compile("\\b([1-5]\\d{2})\\b");
    private static final Pattern CONCRETE_FIELD_NAME =
            Pattern.compile("\\b([a-z][a-z0-9]*_[a-z0-9_]+)\\b");
    private static final Pattern CONCRETE_NUMBER_LIMIT =
            Pattern.compile("\\b(\\d{2,})\\b");

    /**
     * Нейтральная замена для expectedResult, если всё утверждение было домыслом.
     */
    private static final String NEUTRAL_EXPECTED_RESULT =
            "Поведение соответствует требованиям (см. уточнение)";
    /**
     * Нейтральная замена для шага, если из него пришлось вырезать домысел.
     */
    private static final String NEUTRAL_STEP_SUFFIX =
            " (точное поведение уточняется)";

    private final OpenRouterClient openRouter;
    private final ObjectMapper mapper;
    private final AppProperties appProps;

    public record GenerationResult(List<TestCase> testCases, List<Clarification> clarifications) {
    }

    public GenerationResult generate(String markdown) {
        String prompt = appProps.getPrompts().getGenerate()
                .replace("{{schema}}", appProps.getPrompts().getJsonSchema());
        String response = openRouter.complete(prompt, markdown);
        return parseResponse(response, markdown);
    }

    public GenerationResult answer(String markdown, String existingJson, String answersJson) {
        String prompt = appProps.getPrompts().getAnswers()
                .replace("{{schema}}", appProps.getPrompts().getJsonSchema());
        String userText = "## Документ\n" + markdown
                + "\n\n## Ранее сгенерированные тест-кейсы\n" + existingJson
                + "\n\n## Ответы на уточнения\n" + answersJson;
        String response = openRouter.complete(prompt, userText);
        return parseResponse(response, markdown);
    }

    private GenerationResult parseResponse(String json, String sourceMarkdown) {
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

            List<TestCase> testCases = mapper.convertValue(
                    map.getOrDefault("testCases", List.of()),
                    new TypeReference<List<TestCase>>() {
                    });
            List<Clarification> clarifications = mapper.convertValue(
                    map.getOrDefault("clarifications", List.of()),
                    new TypeReference<List<Clarification>>() {
                    });

            // 1) Структурная валидация — здесь 502 корректен: ответ модели реально сломан.
            validateTestCases(testCases);
            validateClarifications(clarifications);

            // 2) Санитизация домыслов — НЕ бросает исключение.
            //    Вырезает неподтверждённые токены и добавляет clarification.
            List<Clarification> groundingClarifications = new ArrayList<>();
            List<TestCase> sanitized = sanitizeAgainstDocument(testCases, sourceMarkdown, groundingClarifications);

            // 3) Объединяем clarifications модели и наши, дедуп по requirementRef.
            List<Clarification> merged = new ArrayList<>(clarifications);
            merged.addAll(groundingClarifications);

            return new GenerationResult(sanitized, dedupeClarifications(merged));
        } catch (GenerationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Ошибка парсинга JSON: {}", json, e);
            throw new GenerationException("Модель вернула невалидный JSON: " + e.getMessage(), e);
        }
    }

    private void validateTestCases(List<TestCase> cases) {
        Set<String> seenIds = new HashSet<>();
        for (TestCase tc : cases) {
            if (isBlank(tc.id()) || !ID_PATTERN.matcher(tc.id()).matches()) {
                throw new GenerationException("Некорректный id тест-кейса: " + tc.id());
            }
            if (!seenIds.add(tc.id())) {
                throw new GenerationException("Дубликат id тест-кейса: " + tc.id());
            }
            if (isBlank(tc.type()) || !ALLOWED_TYPES.contains(tc.type())) {
                throw new GenerationException("Недопустимый type: " + tc.type());
            }
            if (isBlank(tc.title()) || tc.title().length() > 200) {
                throw new GenerationException("Некорректный title для " + tc.id());
            }
            if (isBlank(tc.requirementRef())) {
                throw new GenerationException("requirementRef пуст для " + tc.id());
            }
            if (tc.preconditions() == null) {
                throw new GenerationException("preconditions отсутствует для " + tc.id());
            }
            if (tc.steps() == null || tc.steps().isEmpty()) {
                throw new GenerationException("steps пуст для " + tc.id());
            }
            if (isBlank(tc.expectedResult())) {
                throw new GenerationException("expectedResult пуст для " + tc.id());
            }
            if (tc.priority() == null || !ALLOWED_PRIORITIES.contains(tc.priority())) {
                throw new GenerationException("Некорректный priority для " + tc.id() + ": " + tc.priority());
            }
        }
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
     * Санитизация «опоры на документ». НЕ бросает исключение:
     * — вырезает из expectedResult/steps конкретные токены (HTTP-код, snake_case-поле,
     * числовой лимит), которых нет ни в документе, ни в блоке привязанного требования;
     * — если после вырезания текст стал пустым / потерял смысл — подставляет нейтральную формулировку;
     * — добавляет по одному clarification на каждый requirementRef, где были домыслы.
     * <p>
     * 502 остаётся только для структурно сломанного ответа (validateTestCases/validateClarifications).
     */
    private List<TestCase> sanitizeAgainstDocument(
            List<TestCase> cases,
            String sourceMarkdown,
            List<Clarification> outClarifications) {

        if (sourceMarkdown == null || sourceMarkdown.isBlank()) {
            return cases;
        }
        String docLower = sourceMarkdown.toLowerCase(Locale.ROOT);
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
                    nullSafe(tc.expectedResult()), docLower, refLower, ungrounded);
            if (sanitizedExpected.isBlank()) {
                sanitizedExpected = NEUTRAL_EXPECTED_RESULT;
            }

            List<String> sanitizedSteps = new ArrayList<>();
            if (tc.steps() != null) {
                for (String step : tc.steps()) {
                    String sanitized = sanitizeText(nullSafe(step), docLower, refLower, ungrounded);
                    if (sanitized.isBlank()) {
                        // Шаг не должен исчезнуть — иначе steps станет пустым.
                        sanitized = NEUTRAL_STEP_SUFFIX.trim();
                    }
                    sanitizedSteps.add(sanitized);
                }
            }

            if (!ungrounded.isEmpty()) {
                log.warn("Тест-кейс {}: вырезаны неподтверждённые утверждения {} (требование {})",
                        tc.id(), ungrounded, ref);
                if (clarificationRefsAdded.add(ref)) {
                    outClarifications.add(new Clarification(
                            ref,
                            "Уточните ожидаемое поведение: в документе нет утверждений "
                                    + String.join(", ", ungrounded)
                                    + ", но они были указаны в тест-кейсе",
                            "Модель домыслила конкретику, отсутствующую в требовании "
                                    + ref + "; тест-кейс санитизирован"));
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

    /**
     * Вырезает из текста конкретные токены, отсутствующие в документе/требовании.
     * Найденные токены складывает в {@code ungrounded}. Возвращает остаток текста.
     */
    private String sanitizeText(String text, String docLower, String refLower, Set<String> ungrounded) {
        if (text.isBlank()) {
            return text;
        }
        String out = text;

        // HTTP-коды
        out = replaceUngrounded(out, CONCRETE_HTTP_CODE, 1, docLower, refLower, ungrounded,
                token -> "HTTP " + token);

        // snake_case-поля
        out = replaceUngrounded(out, CONCRETE_FIELD_NAME, 1, docLower, refLower, ungrounded,
                token -> "поле '" + token + "'");

        // числовые лимиты
        out = replaceUngrounded(out, CONCRETE_NUMBER_LIMIT, 1, docLower, refLower, ungrounded,
                token -> "число " + token);

        // Чистим возможные артефакты "  ,", двойные пробелы, висящие запятые/скобки.
        out = out.replaceAll("\\s*,\\s*", ", ")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("\\s+([.,;:])", "$1")
                .replaceAll("^[\\s,;:]+", "")
                .replaceAll("[\\s,;:]+$", "")
                .trim();
        return out;
    }

    private String replaceUngrounded(
            String text,
            Pattern pattern,
            int group,
            String docLower,
            String refLower,
            Set<String> ungrounded,
            java.util.function.Function<String, String> description) {

        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean changed = false;
        while (m.find()) {
            String token = m.group(group);
            String tokenLower = token.toLowerCase(Locale.ROOT);
            boolean grounded = containsToken(docLower, tokenLower)
                    || containsToken(refLower, tokenLower);
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

    private List<Clarification> dedupeClarifications(List<Clarification> src) {
        Map<String, Clarification> byRef = new LinkedHashMap<>();
        for (Clarification c : src) {
            byRef.merge(c.requirementRef(), c,
                    (a, b) -> new Clarification(a.requirementRef(),
                            a.question() + " / " + b.question(),
                            a.reason() + " / " + b.reason()));
        }
        return new ArrayList<>(byRef.values());
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
