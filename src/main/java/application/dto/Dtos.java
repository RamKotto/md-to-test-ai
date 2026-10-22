package application.dto;

import java.util.List;

public class Dtos {

    /**
     * Один из двух вариантов:
     * - markdown: готовый Markdown-текст
     * - pdfToMdUrl: абсолютный http(s)-URL на .md, отданный pdf-to-md
     * filename — необязательное имя исходного документа.
     */
    public record GenerateRequest(String markdown, String pdfToMdUrl, String filename) {
        public boolean hasMarkdown() {
            return markdown != null && !markdown.isBlank();
        }

        public boolean hasPdfToMdUrl() {
            return pdfToMdUrl != null && !pdfToMdUrl.isBlank();
        }

        public boolean hasFilename() {
            return filename != null && !filename.isBlank();
        }
    }

    public record AnswerRequest(List<Answer> answers) {
        public record Answer(String requirementRef, String answer) {
        }
    }

    /**
     * Тип уточнения.
     * MISSING_REQUIREMENT  — модель не знает поведение, это настоящий вопрос к пользователю.
     * UNGROUNDED_ASSUMPTION — санитайзер вырезал домысел. Пользователю НЕ показывается,
     * идёт только в лог/метрику.
     */
    public enum ClarificationType {
        MISSING_REQUIREMENT,
        UNGROUNDED_ASSUMPTION
    }

    public record Clarification(
            String requirementRef,
            String question,
            String reason,
            ClarificationType type
    ) {
        /**
         * Backward-compatible конструктор: по умолчанию — настоящий вопрос.
         */
        public Clarification(String requirementRef, String question, String reason) {
            this(requirementRef, question, reason, ClarificationType.MISSING_REQUIREMENT);
        }
    }

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

    public record GeneratedFile(String id, String filename, String downloadUrl) {
    }

    public record GenerateResponse(
            String taskId,
            String status,
            List<TestCase> testCases,
            List<Clarification> clarifications,
            List<GeneratedFile> files
    ) {
    }
}
