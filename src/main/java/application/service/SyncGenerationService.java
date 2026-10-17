package application.service;

import application.dto.Dtos.Clarification;
import application.dto.Dtos.TestCase;
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
    private final ObjectMapper mapper = new ObjectMapper();

    public record GenerationResult(List<TestCase> testCases, List<Clarification> clarifications) {
    }

    public GenerationResult generate(String markdown) {
        String response = openRouter.complete(JSON_PROMPT, markdown);
        return parseResponse(response);
    }

    public GenerationResult answer(String markdown, String existingJson, String answersJson) {
        String userText = "## Документ\n" + markdown + "\n\n## Ранее сгенерированные\n" + existingJson + "\n\n## Ответы\n" + answersJson;
        String response = openRouter.complete(ANSWERS_PROMPT, userText);
        return parseResponse(response);
    }

    private GenerationResult parseResponse(String json) {
        try {
            String cleaned = json.replaceAll("^```json", "").replaceAll("```$", "").trim();
            Map<String, Object> map = mapper.readValue(cleaned, new TypeReference<>() {
            });
            List<TestCase> testCases = mapper.convertValue(map.getOrDefault("testCases", List.of()), new TypeReference<>() {
            });
            List<Clarification> clarifications = mapper.convertValue(map.getOrDefault("clarifications", List.of()), new TypeReference<>() {
            });
            return new GenerationResult(testCases, clarifications);
        } catch (Exception e) {
            log.error("Ошибка парсинга JSON: {}", json, e);
            throw new RuntimeException("Модель вернула невалидный JSON");
        }
    }

    private static final String JSON_PROMPT = """
            Ты — тестировщик. На вход приходит Markdown-документ (ТЗ или Swagger).
            Верни СТРОГО JSON (без Markdown-оберток, без пояснений).
                        
            Схема:
            {
              "testCases": [
                {
                  "id": "TC-UI-001",
                  "type": "UI",
                  "title": "Название",
                  "requirementRef": "REQ-001",
                  "steps": ["Шаг 1", "Шаг 2"],
                  "expectedResult": "Ожидаемый результат"
                }
              ],
              "clarifications": [
                {
                  "requirementRef": "REQ-005",
                  "question": "Вопрос",
                  "reason": "Причина"
                }
              ]
            }
                        
            Правила:
            1. Не выдумывай данные. Если не хватает — добавь в clarifications.
            2. Только JSON.
                        
            Текст документа:
            """;

    private static final String ANSWERS_PROMPT = """
            Ты — тестировщик. Тебе дан документ, ранее сгенерированные тест-кейсы и ответы на вопросы.
            Дополни список тест-кейсов новыми, используя ответы.
            Верни СТРОГО JSON по той же схеме.
            Не дублируй существующие id. Если ответ неполный — добавь новый вопрос в clarifications.
            Только JSON.
            """;
}
