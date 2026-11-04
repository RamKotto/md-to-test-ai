package application.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки OpenRouter, привязанные к префиксу {@code openrouter}.
 * <p>
 * Используются {@link application.service.OpenRouterClient} и бином {@code openRouterRestClient}
 * из {@link HttpConfig}. Значения по умолчанию класса могут отличаться от значений в
 * {@code application.yml}; приложение использует значения из yml.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "openrouter")
public class OpenRouterProperties {

    /** Базовый URL API OpenRouter, должен быть http(s)-URL. */
    @NotBlank
    @Pattern(regexp = "^https?://.+", message = "openrouter.base-url должен быть http(s)-URL")
    private String baseUrl = "https://openrouter.ai/api/v1";

    /**
     * API-ключ OpenRouter, передаётся в заголовке {@code Authorization}.
     * Обязательное поле: пустое значение не позволяет поднять контекст приложения.
     */
    @NotBlank
    private String apiKey;

    /** Идентификатор модели, передаётся в поле {@code model} запроса. */
    @NotBlank
    private String model = "openrouter/free";

    /** Максимальное число токенов ответа модели, передаётся в поле {@code max_tokens} (минимум 1). */
    @Min(1)
    private int maxOutputTokens = 50000;

    /** Таймаут чтения ответа OpenRouter, в секундах (минимум 1). */
    @Min(1)
    private int timeoutSeconds = 600;

    /** Таймаут установки соединения с OpenRouter, в секундах (минимум 1). */
    @Min(1)
    private int connectTimeoutSeconds = 30;

    /** Значение заголовка {@code HTTP-Referer}, которое OpenRouter использует для идентификации приложения. */
    private String referer = "http://localhost:8082";

    /** Значение заголовка {@code X-Title}, название приложения для OpenRouter. */
    private String title = "md-to-tests";
}
