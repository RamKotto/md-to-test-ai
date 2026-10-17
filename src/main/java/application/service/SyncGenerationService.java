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

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncGenerationService {

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
            List<TestCase> testCases = mapper.convertValue(
                    map.getOrDefault("testCases", List.of()),
                    new TypeReference<List<TestCase>>() {
                    });
            List<Clarification> clarifications = mapper.convertValue(
                    map.getOrDefault("clarifications", List.of()),
                    new TypeReference<List<Clarification>>() {
                    });
            return new GenerationResult(testCases, clarifications);
        } catch (Exception e) {
            log.error("Ошибка парсинга JSON: {}", json, e);
            throw new GenerationException("Модель вернула невалидный JSON");
        }
    }
}
