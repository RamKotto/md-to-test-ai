package application.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Data
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    @Valid
    private Storage storage = new Storage();

    @Valid
    private PdfToMd pdfToMd = new PdfToMd();

    @Valid
    private Sessions sessions = new Sessions();

    @Valid
    private Prompts prompts = new Prompts();

    @Data
    public static class Storage {
        private Path directory = Path.of("./data/tests");
        @NotBlank
        private String baseUrl = "http://localhost:8082";
    }

    @Data
    public static class PdfToMd {
        @NotBlank
        private String baseUrl = "http://localhost:8080";
        @Min(1)
        private int connectTimeoutSeconds = 10;
        @Min(1)
        private int readTimeoutSeconds = 120;
        /**
         * Хосты, с которых разрешено скачивать Markdown.
         * Пустой список = разрешён только хост из base-url.
         */
        private List<String> allowedHosts = new ArrayList<>();
    }

    @Data
    public static class Sessions {
        @Min(1)
        private int ttlMinutes = 120;
        @Min(1)
        private int maxCount = 1000;
        @Min(1)
        private int maxMarkdownChars = 1_000_000;
    }

    @Data
    public static class Prompts {
        @NotBlank
        private String jsonSchema;
        @NotBlank
        private String generate;
        @NotBlank
        private String answers;
    }
}
