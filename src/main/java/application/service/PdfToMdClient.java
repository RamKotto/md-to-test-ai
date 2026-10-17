package application.service;

import application.config.AppProperties;
import application.exception.PdfToMdClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PdfToMdClient {

    private final RestClient http;
    private final Set<String> allowedHosts;

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
    }

    public String fetchMarkdown(String absoluteUrl) {
        URI uri = validateUrl(absoluteUrl);
        try {
            var entity = http.get().uri(uri).retrieve().toEntity(String.class);
            String body = entity.getBody();
            if (body == null || body.isBlank()) {
                throw new PdfToMdClientException("pdf-to-md вернул пустой Markdown: " + absoluteUrl);
            }
            return body;
        } catch (PdfToMdClientException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Не удалось скачать Markdown из pdf-to-md: {}", absoluteUrl, e);
            throw new PdfToMdClientException("Не удалось скачать Markdown: " + e.getMessage(), e);
        }
    }

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
