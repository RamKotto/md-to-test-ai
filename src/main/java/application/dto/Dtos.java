package application.dto;

import java.util.List;

public class Dtos {

    public record GenerateRequest(String markdown, String filename) {
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

    public record GenerateResponse(
            String taskId, String status,
            List<TestCase> testCases, List<Clarification> clarifications
    ) {
    }
}
