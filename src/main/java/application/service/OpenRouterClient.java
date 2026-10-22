package application.service;

import application.config.OpenRouterProperties;
import application.exception.GenerationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
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
        // IMPROVEMENT: явная проверка ключа с понятным сообщением
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            log.warn("openrouter.api-key не задан — запросы к OpenRouter будут падать");
        }
    }

    @SuppressWarnings("unchecked")
    public String complete(String systemPrompt, String userText) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", props.getModel());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userText)
        ));
        body.put("temperature", 0.0);
        body.put("max_tokens", props.getMaxOutputTokens());
        body.put("response_format", Map.of("type", "json_object"));

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
            // IMPROVEMENT: логируем usage для диагностики
            Object usage = response.get("usage");
            if (usage != null) {
                log.debug("OpenRouter usage: {}", usage);
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
        } catch (HttpStatusCodeException e) {
            String errBody = truncate(e.getResponseBodyAsString(), 800);
            log.error("OpenRouter HTTP {}: {}", e.getStatusCode(), errBody);
            throw new GenerationException("OpenRouter HTTP " + e.getStatusCode() + ": " + errBody, e);
        } catch (Exception e) {
            log.error("Ошибка OpenRouter", e);
            throw new GenerationException("OpenRouter: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
