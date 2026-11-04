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

/**
 * Клиент OpenRouter Chat Completions API: единственная точка обмена с LLM в модуле.
 * <p>
 * Отправляет системный и пользовательский промпты на {@code /chat/completions} с
 * {@code temperature = 0.0}, {@code max_tokens = openrouter.max-output-tokens} и
 * {@code response_format = {"type": "json_object"}}. Повторных попыток нет: любой сбой сразу
 * превращается в {@link GenerationException}.
 */
@Slf4j
@Service
public class OpenRouterClient {

    private final OpenRouterProperties props;
    private final RestClient http;

    /**
     * Создаёт клиент и проверяет наличие API-ключа. Пустой ключ дополнительно логируется
     * предупреждением. Штатный запуск приложения отклоняет пустой ключ раньше, на этапе валидации
     * {@link OpenRouterProperties}.
     *
     * @param props                настройки OpenRouter
     * @param openRouterRestClient клиент с базовым URL и заголовками, см. {@code HttpConfig}
     */
    public OpenRouterClient(OpenRouterProperties props, RestClient openRouterRestClient) {
        this.props = props;
        this.http = openRouterRestClient;
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            log.warn("openrouter.api-key не задан — запросы к OpenRouter будут падать");
        }
    }

    /**
     * Выполняет запрос завершения чата и возвращает текст первого варианта ответа.
     * <p>
     * Usage логируется на уровне DEBUG, если его вернул OpenRouter. Текст тела HTTP-ошибки
     * обрезается до 800 символов.
     *
     * @param systemPrompt системный промпт
     * @param userText     пользовательский текст (документ и дополнительные данные)
     * @return содержимое {@code choices[0].message.content}
     * @throws GenerationException если ответ пустой, не содержит {@code choices} или {@code content},
     *                             вернулся HTTP-статус ошибки, либо произошёл сетевой сбой или таймаут
     */
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

    /**
     * Обрезает строку до {@code max} символов и добавляет многоточие, если текст был длиннее.
     *
     * @param s   исходная строка, может быть {@code null}
     * @param max максимальная длина без многоточия
     * @return обрезанная строка, пустая строка для {@code null}
     */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
