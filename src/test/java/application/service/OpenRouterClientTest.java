package application.service;

import application.exception.GenerationException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Тесты проверки тела ответа OpenRouter в {@link OpenRouterClient#extractContent}.
 * Сеть не используется: тестируется вынесенный метод разбора ответа.
 */
class OpenRouterClientTest {

    /**
     * Собирает тело ответа chat/completions с одним вариантом.
     *
     * @param finishReason значение {@code finish_reason} или {@code null}, если поля нет
     * @param content      содержимое {@code message.content}
     * @return тело ответа
     */
    private static Map<String, Object> response(String finishReason, Object content) {
        Map<String, Object> message = new HashMap<>();
        message.put("content", content);
        Map<String, Object> choice = new HashMap<>();
        choice.put("message", message);
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("choices", List.of(choice));
        return body;
    }

    /**
     * Обычное завершение: содержимое возвращается как есть.
     */
    @Test
    void stopFinishReason_returnsContent() {
        assertThat(OpenRouterClient.extractContent(response("stop", "{\"testCases\":[]}")))
                .isEqualTo("{\"testCases\":[]}");
    }

    /**
     * Обрыв по max_tokens: JSON неполный, поэтому ответ отклоняется с подсказкой.
     */
    @Test
    void lengthFinishReason_throwsWithHint() {
        assertThatThrownBy(() -> OpenRouterClient.extractContent(response("length", "{\"testCases\":[{")))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("обрезан по max_tokens")
                .hasMessageContaining("openrouter.max-output-tokens");
    }

    /**
     * Без поля finish_reason ответ принимается, как и раньше.
     */
    @Test
    void missingFinishReason_isAccepted() {
        assertThat(OpenRouterClient.extractContent(response(null, "{}"))).isEqualTo("{}");
    }

    /**
     * Пустой список choices — ошибка ответа.
     */
    @Test
    void emptyChoices_throws() {
        Map<String, Object> body = new HashMap<>();
        body.put("choices", List.of());

        assertThatThrownBy(() -> OpenRouterClient.extractContent(body))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("choices");
    }

    /**
     * Отсутствующее содержимое сообщения — ошибка ответа.
     */
    @Test
    void nullContent_throws() {
        assertThatThrownBy(() -> OpenRouterClient.extractContent(response("stop", null)))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("Пустой content");
    }

    /**
     * Пустое тело ответа — ошибка.
     */
    @Test
    void nullResponse_throws() {
        assertThatThrownBy(() -> OpenRouterClient.extractContent(null))
                .isInstanceOf(GenerationException.class);
    }
}
