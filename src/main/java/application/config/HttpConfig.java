package application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Конфигурация HTTP-клиентов приложения.
 * <p>
 * Создаёт два бина {@link RestClient}: для OpenRouter (с заголовками авторизации и идентификации)
 * и для pdf-to-md (только таймауты). Оба используют {@link SimpleClientHttpRequestFactory}
 * с таймаутами соединения и чтения из соответствующих настроек.
 */
@Configuration
public class HttpConfig {

    /**
     * Создаёт клиент для OpenRouter: базовый URL из {@code openrouter.base-url}, заголовок
     * {@code Authorization: Bearer <api-key>}, {@code HTTP-Referer} и {@code X-Title}.
     * Ключ фиксируется в заголовке при создании бина.
     *
     * @param props настройки OpenRouter
     * @return настроенный {@link RestClient} с именем {@code openRouterRestClient}
     */
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

    /**
     * Создаёт клиент для pdf-to-md с таймаутами из {@code app.pdf-to-md}.
     * Заголовков по умолчанию нет.
     *
     * @param appProps настройки приложения, включая {@code app.pdf-to-md}
     * @return настроенный {@link RestClient} с именем {@code pdfToMdRestClient}
     */
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
