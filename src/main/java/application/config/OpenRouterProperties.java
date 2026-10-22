package application.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "openrouter")
public class OpenRouterProperties {

    @NotBlank
    @Pattern(regexp = "^https?://.+", message = "openrouter.base-url должен быть http(s)-URL")
    private String baseUrl = "https://openrouter.ai/api/v1";

    @NotBlank
    private String apiKey;

    @NotBlank
    private String model = "openrouter/free";

    @Min(1)
    private int maxOutputTokens = 50000;

    @Min(1)
    private int timeoutSeconds = 600;

    @Min(1)
    private int connectTimeoutSeconds = 30;

    private String referer = "http://localhost:8082";
    private String title = "md-to-tests";
}
