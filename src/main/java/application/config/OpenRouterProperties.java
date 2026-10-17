package application.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "openrouter")
public class OpenRouterProperties {
    private String baseUrl = "https://openrouter.ai/api/v1";
    private String apiKey;
    private String model = "qwen/qwen-2.5-vl-7b-instruct";
    private int maxOutputTokens = 8192;
    private int timeoutSeconds = 600;
    private String referer = "http://localhost:8082";
    private String title = "md-to-tests";
}