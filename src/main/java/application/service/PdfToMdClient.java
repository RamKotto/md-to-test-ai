package application.service;

import application.config.AppProperties;
import application.exception.PdfToMdClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Клиент для скачивания Markdown-документа из сервиса pdf-to-md по абсолютному URL.
 * <p>
 * Защита от SSRF: схема URL должна быть {@code http} или {@code https}, хост должен входить в
 * allowlist. Allowlist берётся из {@code app.pdf-to-md.allowed-hosts}; если список пуст, используется
 * хост из {@code app.pdf-to-md.base-url}. Порт и путь не проверяются.
 */
@Slf4j
@Service
public class PdfToMdClient {

    private final RestClient http;
    private final Set<String> allowedHosts;

    /**
     * Создаёт клиент и вычисляет allowlist хостов в нижнем регистре.
     * <p>
     * Значения {@code allowed-hosts} приводятся к нижнему регистру и очищаются от пробелов по краям.
     * Список логируется на уровне INFO.
     *
     * @param pdfToMdRestClient клиент pdf-to-md с таймаутами, см. {@code HttpConfig}
     * @param appProps          настройки приложения, включая {@code app.pdf-to-md}
     */
    public PdfToMdClient(RestClient pdfToMdRestClient, AppProperties appProps) {
        this.http = pdfToMdRestClient;
        var configured = appProps.getPdfToMd().getAllowedHosts();
        if (configured == null || configured.isEmpty()) {
            String baseHost = URI.create(appProps.getPdfToMd().getBaseUrl()).getHost();
            this.allowedHosts = Set.of(baseHost.toLowerCase());
        } else {
            this.allowedHosts = configured.stream()
                    .map(s -> s.toLowerCase().trim())
                    .collect(Collectors.toUnmodifiableSet());
        }
        log.info("PdfToMdClient: разрешённые хосты = {}", allowedHosts);
    }

    /**
     * Скачивает Markdown по абсолютному URL.
     * <p>
     * URL проверяется методом {@link #validateUrl} до запроса. Content-Type ответа сравнивается
     * с {@code text/markdown}, {@code text/plain} и {@code application/octet-stream}. При несовпадении
     * пишется предупреждение, но ответ принимается. Пустое тело отклоняется.
     *
     * @param absoluteUrl абсолютный http(s)-URL на {@code .md}, разрешённый по allowlist
     * @return текст Markdown без изменений
     * @throws IllegalArgumentException   если URL пустой, неверного формата, не http(s) или хост не разрешён
     *                                    (ответ {@code 400})
     * @throws PdfToMdClientException     если запрос завершился ошибкой, либо тело пусто (ответ {@code 502})
     */
    public String fetchMarkdown(String absoluteUrl) {
        URI uri = validateUrl(absoluteUrl);
        log.info("Скачивание Markdown из pdf-to-md: {}", uri);
        try {
            var entity = http.get().uri(uri).retrieve().toEntity(String.class);

            MediaType contentType = entity.getHeaders().getContentType();
            if (contentType != null
                    && !contentType.isCompatibleWith(MediaType.TEXT_MARKDOWN)
                    && !contentType.isCompatibleWith(MediaType.TEXT_PLAIN)
                    && !contentType.isCompatibleWith(MediaType.APPLICATION_OCTET_STREAM)) {
                log.warn("pdf-to-md вернул неожиданный Content-Type: {} для {}", contentType, uri);
            }

            String body = entity.getBody();
            if (body == null || body.isBlank()) {
                throw new PdfToMdClientException("pdf-to-md вернул пустой Markdown: " + absoluteUrl);
            }
            log.info("Скачано из pdf-to-md: {} символов", body.length());
            return body;
        } catch (PdfToMdClientException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Не удалось скачать Markdown из pdf-to-md: {}", absoluteUrl, e);
            throw new PdfToMdClientException("Не удалось скачать Markdown: " + e.getMessage(), e);
        }
    }

    /**
     * Проверяет URL перед запросом: непустой, корректный, схема {@code http} или {@code https}
     * (без учёта регистра) и хост входит в allowlist (сравнение без учёта регистра).
     *
     * @param raw исходная строка URL
     * @return разобранный {@link URI}
     * @throws IllegalArgumentException если любая проверка не пройдена; сообщение содержит причину
     *                                  и, для хоста, список разрешённых хостов
     */
    private URI validateUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("pdfToMdUrl обязателен");
        }
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("pdfToMdUrl имеет неверный формат: " + raw);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("pdfToMdUrl должен быть http(s)-URL");
        }
        String host = uri.getHost();
        if (host == null || !allowedHosts.contains(host.toLowerCase())) {
            throw new IllegalArgumentException(
                    "Хост не разрешён: " + host + ". Разрешены: " + allowedHosts);
        }
        return uri;
    }
}
