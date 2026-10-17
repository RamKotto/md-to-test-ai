package application.service;

import application.config.OpenRouterProperties;
import application.exception.GenerationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OpenRouterClient {

    private final OpenRouterProperties props;
    private final RestClient http;

    public OpenRouterClient(OpenRouterProperties props, RestClient openRouterRestClient) {
        this.props = props;
        this.http = openRouterRestClient;
    }

    @SuppressWarnings("unchecked")
    public String complete(String systemPrompt, String userText) {
        Map<String, Object> body = Map.of(
                "model", props.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userText)
                ),
                "temperature", 0.0,
                "max_tokens", props.getMaxOutputTokens(),
                "response_format", Map.of("type", "json_object")
        );

        try {
            Map<String, Object> response = http.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                throw new GenerationException("Пустой ответ от OpenRouter");
            }
            var choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new GenerationException("OpenRouter не вернул choices");
            }
            var message = (Map<String, Object>) choices.get(0).get("message");
            Object content = message == null ? null : message.get("content");
            if (content == null) {
                throw new GenerationException("Пустой content в ответе OpenRouter");
            }
            return content.toString();
        } catch (GenerationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Ошибка OpenRouter", e);
            throw new GenerationException("OpenRouter: " + e.getMessage(), e);
        }
    }
}
