package application.dto;

import java.util.List;

public class Dtos {

    /**
     * Один из двух вариантов:
     * - markdown: готовый Markdown-текст
     * - pdfToMdUrl: абсолютный http(s)-URL на .md, отданный pdf-to-md
     * filename — необязательное имя исходного документа. Если pdf-to-md вернул
     * filename в ConvertResponse, можно передать его сюда; используется
     * для префиксации имён сгенерированных файлов (например, "swagger_TC-UI-001_login.md").
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

    public record Clarification(String requirementRef, String question, String reason) {
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
