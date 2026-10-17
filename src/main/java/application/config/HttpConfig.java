package application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.time.Duration;

@Configuration
public class HttpConfig {
    @Bean
    public RestClient openRouterRestClient(OpenRouterProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);
        factory.setReadTimeout((int) Duration.ofSeconds(props.getTimeoutSeconds()).toMillis());

        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + (props.getApiKey() == null
                        ? "" : props.getApiKey()))
                .defaultHeader("HTTP-Referer", props.getReferer())
                .defaultHeader("X-Title", props.getTitle())
                .build();
    }
}