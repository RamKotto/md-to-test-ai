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
     * Конкретные утверждения, которые модель часто «домысливает», если их нет в документе.
     * Группа 1 — HTTP-код, группа 2 — snake_case-имя поля/параметра, группа 3 — числовой лимит.
     */
    private static final Pattern CONCRETE_HTTP_CODE =
            Pattern.compile("\\b([1-5]\\d{2})\\b");
    private static final Pattern CONCRETE_FIELD_NAME =
            Pattern.compile("\\b([a-z][a-z0-9]*_[a-z0-9_]+)\\b");
    private static final Pattern CONCRETE_NUMBER_LIMIT =
            Pattern.compile("\\b(\\d{2,})\\b");

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

            validateTestCases(testCases);
            validateClarifications(clarifications);
            validateGroundingInDocument(testCases, sourceMarkdown);
            return new GenerationResult(testCases, dedupeClarifications(clarifications));
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
     * Пост-валидация «опоры на документ»: каждое конкретное утверждение
     * в expectedResult и steps (HTTP-код, snake_case-имя поля, числовой лимит)
     * должно встречаться либо в исходном Markdown, либо в тексте привязанного требования.
     * <p>
     * Цель — отсечь домыслы вида «HTTP 400, error: true» в тест-кейсе на требование,
     * которое таких утверждений не содержит (см. кейс minutely_15 вне зоны покрытия).
     * <p>
     * Если проверка не проходит — это GenerationException → 502: модель обязана была
     * либо сформулировать нейтрально, либо вынести вопрос в clarifications.
     */
    private void validateGroundingInDocument(List<TestCase> cases, String sourceMarkdown) {
        if (sourceMarkdown == null || sourceMarkdown.isBlank()) {
            return;
        }
        String docLower = sourceMarkdown.toLowerCase(Locale.ROOT);

        for (TestCase tc : cases) {
            String ref = nullSafe(tc.requirementRef());
            String requirementText = extractRequirementBlock(sourceMarkdown, ref);
            String refLower = requirementText == null
                    ? null
                    : requirementText.toLowerCase(Locale.ROOT);

            List<String> ungrounded = new ArrayList<>();

            // expectedResult
            collectUngroundedTokens(nullSafe(tc.expectedResult()), docLower, refLower, ungrounded);
            // steps
            if (tc.steps() != null) {
                for (String step : tc.steps()) {
                    collectUngroundedTokens(nullSafe(step), docLower, refLower, ungrounded);
                }
            }

            if (!ungrounded.isEmpty()) {
                throw new GenerationException(
                        "Тест-кейс " + tc.id() + " содержит утверждения, которых нет в документе "
                                + "и в привязанном требовании " + ref + ": " + ungrounded
                                + ". Сформулируйте expectedResult/steps нейтрально "
                                + "или вынесите вопрос в clarifications.");
            }
        }
    }

    /**
     * Извлекает из Markdown блок требования по его ID.
     * Ищем заголовок, содержащий ref, и берём текст до следующего заголовка того же или более высокого уровня.
     * Если не нашли — возвращаем null (тогда сверяем только с полным документом).
     */
    private String extractRequirementBlock(String markdown, String ref) {
        if (ref.isBlank() || "N/A".equalsIgnoreCase(ref)) {
            return null;
        }
        String[] lines = markdown.split("\n", -1);
        int startLine = -1;
        int headingLevel = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains(ref)) {
                // Определяем уровень заголовка, если это заголовок
                Matcher m = Pattern.compile("^(#{1,6})\\s").matcher(line);
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

    /**
     * Собирает токены из текста, которых нет ни в полном документе, ни в блоке требования.
     */
    private void collectUngroundedTokens(String text, String docLower, String refLower, List<String> out) {
        if (text.isBlank()) {
            return;
        }
        String textLower = text.toLowerCase(Locale.ROOT);

        Matcher http = CONCRETE_HTTP_CODE.matcher(textLower);
        while (http.find()) {
            String token = http.group(1);
            if (!containsToken(docLower, token) && !containsToken(refLower, token)) {
                out.add("HTTP " + token);
            }
        }

        Matcher field = CONCRETE_FIELD_NAME.matcher(textLower);
        while (field.find()) {
            String token = field.group(1);
            if (!containsToken(docLower, token) && !containsToken(refLower, token)) {
                out.add("поле '" + token + "'");
            }
        }

        Matcher num = CONCRETE_NUMBER_LIMIT.matcher(textLower);
        while (num.find()) {
            String token = num.group(1);
            // Числовые лимиты проверяем как отдельные числа, чтобы "400" внутри "1400" не считалось совпадением
            if (!containsToken(docLower, token) && !containsToken(refLower, token)) {
                out.add("число " + token);
            }
        }
    }

    private boolean containsToken(String haystackLower, String tokenLower) {
        if (haystackLower == null) {
            return false;
        }
        // Границы слова: цифры/буквы/подчёркивание не должны прилипать
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
