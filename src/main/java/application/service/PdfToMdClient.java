package application.service;

import application.exception.PdfToMdClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Slf4j
@Service
public class PdfToMdClient {

    private final RestClient http;

    public PdfToMdClient(RestClient pdfToMdRestClient) {
        this.http = pdfToMdRestClient;
    }

    public String fetchMarkdown(String absoluteUrl) {
        if (absoluteUrl == null || !absoluteUrl.startsWith("http")) {
            throw new IllegalArgumentException("pdfToMdUrl должен быть абсолютным http(s)-URL");
        }
        try {
            var entity = http.get().uri(absoluteUrl).retrieve().toEntity(String.class);
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
}
