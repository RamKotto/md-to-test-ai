package application.dto;

import java.util.List;

/**
 * Контейнер DTO REST API сервиса в виде вложенных record-типов и enum.
 * <p>
 * Имена полей совпадают с JSON-ключами запросов и ответов. Поля {@code null} в ответах
 * сериализуются как {@code null}, не отфильтровываются.
 */
public class Dtos {

    /**
     * Тело запроса {@code POST /api/generate}. Должен быть задан один из источников Markdown.
     *
     * @param markdown   готовый Markdown-текст; имеет приоритет над {@code pdfToMdUrl}
     * @param pdfToMdUrl абсолютный http(s)-URL на {@code .md}, полученный от pdf-to-md;
     *                   хост проверяется по allowlist
     * @param filename   необязательное имя исходного документа; из него строится префикс файлов
     */
    public record GenerateRequest(String markdown, String pdfToMdUrl, String filename) {

        /**
         * @return {@code true}, если {@code markdown} задан и не состоит только из пробельных символов
         */
        public boolean hasMarkdown() {
            return markdown != null && !markdown.isBlank();
        }

        /**
         * @return {@code true}, если {@code pdfToMdUrl} задан и не состоит только из пробельных символов
         */
        public boolean hasPdfToMdUrl() {
            return pdfToMdUrl != null && !pdfToMdUrl.isBlank();
        }

        /**
         * @return {@code true}, если {@code filename} задан и не состоит только из пробельных символов
         */
        public boolean hasFilename() {
            return filename != null && !filename.isBlank();
        }
    }

    /**
     * Тело запроса {@code POST /api/generate/{taskId}/answers}.
     *
     * @param answers ответы пользователя на уточнения; список не валидируется
     */
    public record AnswerRequest(List<Answer> answers) {

        /**
         * Ответ пользователя на одно уточнение.
         *
         * @param requirementRef ссылка на требование, к которому относится ответ
         * @param answer         текст ответа
         */
        public record Answer(String requirementRef, String answer) {
        }
    }

    /**
     * Тип уточнения.
     */
    public enum ClarificationType {

        /** Модель не знает поведение, это вопрос к пользователю; влияет на статус {@code NEEDS_CLARIFICATION}. */
        MISSING_REQUIREMENT,

        /**
         * Санитайзер вырезал домысел модели, отсутствующий в документе. Сохраняется в ответе API,
         * но в MD-файлы не выводится и статус прогона не меняет.
         */
        UNGROUNDED_ASSUMPTION
    }

    /**
     * Уточнение к требованию.
     *
     * @param requirementRef ссылка на требование ({@code REQ-…} или {@code N/A})
     * @param question       вопрос к пользователю
     * @param reason         причина вопроса
     * @param type           тип уточнения, см. {@link ClarificationType}
     */
    public record Clarification(
            String requirementRef,
            String question,
            String reason,
            ClarificationType type
    ) {

        /**
         * Создаёт уточнение с типом {@link ClarificationType#MISSING_REQUIREMENT}.
         *
         * @param requirementRef ссылка на требование
         * @param question       вопрос
         * @param reason         причина
         */
        public Clarification(String requirementRef, String question, String reason) {
            this(requirementRef, question, reason, ClarificationType.MISSING_REQUIREMENT);
        }

        /**
         * Фабричный метод для уточнения, у которого модель не указала тип.
         *
         * @param requirementRef ссылка на требование
         * @param question       вопрос
         * @param reason         причина
         * @return уточнение с типом {@link ClarificationType#MISSING_REQUIREMENT}
         */
        public static Clarification missing(String requirementRef, String question, String reason) {
            return new Clarification(requirementRef, question, reason,
                    ClarificationType.MISSING_REQUIREMENT);
        }
    }

    /**
     * Тест-кейс после валидации ответа модели.
     *
     * @param id             идентификатор вида {@code TC-<TYPE>-<NNN>}, уникален в прогоне
     * @param type           один из {@code UI}, {@code API}, {@code E2E}, {@code NEGATIVE},
     *                       {@code SECURITY}, {@code PERFORMANCE}
     * @param title          заголовок, не длиннее 200 символов
     * @param requirementRef ссылка на требование или {@code N/A}
     * @param preconditions  предусловия; может быть пустым списком
     * @param steps          шаги; не пустой список непустых строк
     * @param expectedResult ожидаемый результат, непустой
     * @param priority       один из {@code P0}, {@code P1}, {@code P2}, {@code P3}
     */
    public record TestCase(
            String id,
            String type,
            String title,
            String requirementRef,
            List<String> preconditions,
            List<String> steps,
            String expectedResult,
            String priority
    ) {
    }

    /**
     * Описание записанного файла в ответе API.
     *
     * @param id          идентификатор: id тест-кейса, либо {@code _clarifications}, либо {@code _index}
     * @param filename    имя файла на диске
     * @param downloadUrl абсолютный URL для скачивания через {@code GET /api/files/{filename}}
     */
    public record GeneratedFile(String id, String filename, String downloadUrl) {
    }

    /**
     * Ответ {@code POST /api/generate} и {@code POST /api/generate/{taskId}/answers}.
     *
     * @param taskId         идентификатор прогона; для дальнейших ответов на уточнения
     * @param status         {@code DONE} или {@code NEEDS_CLARIFICATION}
     * @param testCases      тест-кейсы прогона
     * @param clarifications уточнения, включая санитайзерные ({@code UNGROUNDED_ASSUMPTION})
     * @param files          записанные файлы в порядке записи
     */
    public record GenerateResponse(
            String taskId,
            String status,
            List<TestCase> testCases,
            List<Clarification> clarifications,
            List<GeneratedFile> files
    ) {
    }
}
