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

    private final OpenRouterClient openRouter;
    private final ObjectMapper mapper;
    private final AppProperties appProps;

    public record GenerationResult(List<TestCase> testCases, List<Clarification> clarifications) {
    }

    public GenerationResult generate(String markdown) {
        String prompt = appProps.getPrompts().getGenerate()
                .replace("{{schema}}", appProps.getPrompts().getJsonSchema());
        String response = openRouter.complete(prompt, markdown);
        return parseResponse(response);
    }

    public GenerationResult answer(String markdown, String existingJson, String answersJson) {
        String prompt = appProps.getPrompts().getAnswers()
                .replace("{{schema}}", appProps.getPrompts().getJsonSchema());
        String userText = "## Документ\n" + markdown
                + "\n\n## Ранее сгенерированные тест-кейсы\n" + existingJson
                + "\n\n## Ответы на уточнения\n" + answersJson;
        String response = openRouter.complete(prompt, userText);
        return parseResponse(response);
    }

    private GenerationResult parseResponse(String json) {
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
}
