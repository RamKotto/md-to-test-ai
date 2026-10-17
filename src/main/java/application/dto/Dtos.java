package application.dto;

import java.util.List;

public class Dtos {

    public record GenerateRequest(String markdown, String pdfToMdUrl, String filename) {
        public boolean hasMarkdown() {
            return markdown != null && !markdown.isBlank();
        }

        public boolean hasPdfToMdUrl() {
            return pdfToMdUrl != null && !pdfToMdUrl.isBlank();
        }
    }

    public record AnswerRequest(List<Answer> answers) {
        public record Answer(String requirementRef, String answer) {
        }
    }

    public record Clarification(String requirementRef, String question, String reason) {
    }

    public record TestCase(
            String id, String type, String title, String requirementRef,
            List<String> steps, String expectedResult
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
