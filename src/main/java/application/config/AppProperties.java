package application.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * Настройки прикладной логики, привязанные к префиксу {@code app} из {@code application.yml}.
 * <p>
 * Валидируется при старте: при нарушении ограничений контекст не поднимается.
 * Файлы и сессии хранятся в БД (см. {@code docs/storage-contract.md}), поэтому каталога на диске нет.
 * Вложенные группы: {@code app.storage}, {@code app.pdf-to-md}, {@code app.sessions},
 * {@code app.prompts}.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Настройки базового URL для ссылок на скачивание артефактов. */
    @Valid
    private Storage storage = new Storage();

    /** Настройки скачивания Markdown из сервиса pdf-to-md. */
    @Valid
    private PdfToMd pdfToMd = new PdfToMd();

    /** Настройки ограничений сессий уточнений. */
    @Valid
    private Sessions sessions = new Sessions();

    /** Тексты промптов для LLM и JSON-схема ответа. */
    @Valid
    private Prompts prompts = new Prompts();

    /**
     * Параметры хранилища файлов (артефакты лежат в {@code qa.artifacts}).
     */
    @Data
    public static class Storage {

        /**
         * Базовый URL, от которого строится {@code downloadUrl} в ответе: {@code {baseUrl}/api/files/{uuid}}.
         * Должен быть http(s)-URL.
         */
        @NotBlank
        @Pattern(regexp = "^https?://.+", message = "storage.base-url должен быть http(s)-URL")
        private String baseUrl = "http://localhost:8082";
    }

    /**
     * Параметры подключения к сервису pdf-to-md.
     */
    @Data
    public static class PdfToMd {

        /**
         * Базовый URL сервиса pdf-to-md. Используется как хост по умолчанию для allowlist,
         * когда {@link #allowedHosts} пуст. Запросы выполняются по абсолютным URL из запроса клиента.
         * Должен быть http(s)-URL.
         */
        @NotBlank
        @Pattern(regexp = "^https?://.+", message = "pdf-to-md.base-url должен быть http(s)-URL")
        private String baseUrl = "http://localhost:8080";

        /** Таймаут установки соединения с pdf-to-md, в секундах (минимум 1). */
        @Min(1)
        private int connectTimeoutSeconds = 10;

        /** Таймаут чтения ответа pdf-to-md, в секундах (минимум 1). */
        @Min(1)
        private int readTimeoutSeconds = 120;

        /**
         * Хосты, с которых разрешено скачивать Markdown.
         * Пустой список = разрешён только хост из base-url.
         */
        private List<String> allowedHosts = new ArrayList<>();
    }

    /**
     * Ограничения сессий уточнений.
     */
    @Data
    public static class Sessions {

        /**
         * Максимальная длина Markdown-документа в символах, который можно сохранить в сессии (минимум 1).
         * Проверяется до записи, чтобы клиент не получил ошибку уже после записи артефактов.
         */
        @Min(1)
        private int maxMarkdownChars = 1_000_000;
    }

    /**
     * Тексты промптов. Значения задаются в {@code application.yml}; в шаблонах
     * плейсхолдер {@code {{schema}}} заменяется на {@link #jsonSchema}.
     */
    @Data
    public static class Prompts {

        /** JSON-схема ответа модели, подставляемая в шаблоны вместо {@code {{schema}}}. */
        @NotBlank
        private String jsonSchema;

        /** Шаблон промпта первичной генерации тест-кейсов. */
        @NotBlank
        private String generate;

        /** Шаблон промпта обработки ответов пользователя на уточнения. */
        @NotBlank
        private String answers;
    }
}
