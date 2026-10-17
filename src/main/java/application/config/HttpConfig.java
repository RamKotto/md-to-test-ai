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
        factory.setConnectTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(props.getTimeoutSeconds()));
        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + props.getApiKey())
                .defaultHeader("HTTP-Referer", props.getReferer())
                .defaultHeader("X-Title", props.getTitle())
                .build();
    }

    @Bean
    public RestClient pdfToMdRestClient(AppProperties appProps) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(appProps.getPdfToMd().getConnectTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(appProps.getPdfToMd().getReadTimeoutSeconds()));
        return RestClient.builder()
                .baseUrl(appProps.getPdfToMd().getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
