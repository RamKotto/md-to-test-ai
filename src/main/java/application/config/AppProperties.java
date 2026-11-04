package application.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Настройки прикладной логики, привязанные к префиксу {@code app} из {@code application.yml}.
 * <p>
 * Валидируется при старте: при нарушении ограничений контекст не поднимается.
 * Вложенные группы: {@code app.storage}, {@code app.pdf-to-md}, {@code app.sessions},
 * {@code app.prompts}.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Настройки каталога с итоговыми Markdown-файлами и базового URL для скачивания. */
    @Valid
    private Storage storage = new Storage();

    /** Настройки скачивания Markdown из сервиса pdf-to-md. */
    @Valid
    private PdfToMd pdfToMd = new PdfToMd();

    /** Настройки in-memory сессий уточнений. */
    @Valid
    private Sessions sessions = new Sessions();

    /** Тексты промптов для LLM и JSON-схема ответа. */
    @Valid
    private Prompts prompts = new Prompts();

    /**
     * Параметры хранилища файлов.
     */
    @Data
    public static class Storage {

        /**
         * Каталог, куда записываются файлы тест-кейсов. Создаётся при старте приложения.
         * Значение по умолчанию класса относительно рабочей директории; в {@code application.yml}
         * переопределяется на {@code ${user.dir}/data/tests}.
         */
        private Path directory = Path.of("./data/tests");

        /**
         * Базовый URL, от которого строится {@code downloadUrl} в ответе: {@code {baseUrl}/api/files/{имя}}.
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
     * Параметры in-memory сессий уточнений.
     */
    @Data
    public static class Sessions {

        /** Время жизни сессии от момента сохранения, в минутах (минимум 1). */
        @Min(1)
        private int ttlMinutes = 120;

        /**
         * Максимальное число одновременно хранимых сессий (минимум 1).
         * При переполнении вытесняется самая старая по времени создания сессия.
         */
        @Min(1)
        private int maxCount = 1000;

        /**
         * Максимальная длина Markdown-документа в символах, который можно сохранить в сессии (минимум 1).
         * Проверяется до записи файлов, чтобы клиент не получил ошибку при уже записанных файлах.
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
