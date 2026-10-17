package application.controller;

import application.dto.Dtos.*;
import application.service.SyncGenerationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/generate")
@RequiredArgsConstructor
public class GenerationController {

    private final SyncGenerationService generationService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private record Session(String markdown, String testCasesJson) {}

    @PostMapping
    public ResponseEntity<GenerateResponse> generate(@RequestBody GenerateRequest request) {
        if (request.markdown() == null || request.markdown().isBlank()) {
            throw new IllegalArgumentException("Markdown не может быть пустым");
        }

        var result = generationService.generate(request.markdown());
        String taskId = UUID.randomUUID().toString();
        String status = result.clarifications().isEmpty() ? "DONE" : "NEEDS_CLARIFICATION";

        if ("NEEDS_CLARIFICATION".equals(status)) {
            try {
                String json = mapper.writeValueAsString(result.testCases());
                sessions.put(taskId, new Session(request.markdown(), json));
            } catch (Exception e) {
                throw new RuntimeException("Ошибка сериализации", e);
            }
        }

        return ResponseEntity.ok(new GenerateResponse(taskId, status, result.testCases(), result.clarifications()));
    }

    @PostMapping("/{taskId}/answers")
    public ResponseEntity<GenerateResponse> answer(@PathVariable String taskId, @RequestBody AnswerRequest request) {
        Session session = sessions.get(taskId);
        if (session == null) throw new IllegalArgumentException("Сессия не найдена или истекла");

        try {
            String answersJson = mapper.writeValueAsString(request.answers());
            var result = generationService.answer(session.markdown(), session.testCasesJson(), answersJson);
            sessions.remove(taskId);

            return ResponseEntity.ok(new GenerateResponse(
                    taskId,
                    result.clarifications().isEmpty() ? "DONE" : "NEEDS_CLARIFICATION",
                    result.testCases(),
                    result.clarifications()
            ));
        } catch (Exception e) {
            throw new RuntimeException("Ошибка обработки ответов", e);
        }
    }
}