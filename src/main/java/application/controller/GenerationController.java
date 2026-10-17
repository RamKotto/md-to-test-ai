package application.controller;

import application.dto.Dtos.*;
import application.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/generate")
@RequiredArgsConstructor
public class GenerationController {

    private final SyncGenerationService generationService;
    private final TestCaseMarkdownRenderer renderer;
    private final TestStorageService storage;
    private final PdfToMdClient pdfToMdClient;
    private final SessionStore sessionStore;
    private final ObjectMapper mapper;

    @PostMapping
    public ResponseEntity<GenerateResponse> generate(@RequestBody GenerateRequest request) {
        String markdown = resolveMarkdown(request);
        if (markdown.isBlank()) {
            throw new IllegalArgumentException("Markdown не может быть пустым");
        }

        var result = generationService.generate(markdown);
        String taskId = UUID.randomUUID().toString();

        // MD-файлы создаются ВСЕГДА, даже если есть уточнения
        List<GeneratedFile> files = persist(result.testCases(), result.clarifications());
        String status = result.clarifications().isEmpty() ? "DONE" : "NEEDS_CLARIFICATION";

        if ("NEEDS_CLARIFICATION".equals(status)) {
            try {
                String json = mapper.writeValueAsString(result.testCases());
                sessionStore.put(taskId, markdown, json);
            } catch (Exception e) {
                throw new IllegalStateException("Ошибка сериализации сессии", e);
            }
        }

        return ResponseEntity.ok(new GenerateResponse(taskId, status,
                result.testCases(), result.clarifications(), files));
    }

    @PostMapping("/{taskId}/answers")
    public ResponseEntity<GenerateResponse> answer(@PathVariable String taskId,
                                                   @RequestBody AnswerRequest request) {
        SessionStore.Session session = sessionStore.get(taskId);
        if (session == null) {
            throw new IllegalArgumentException("Сессия не найдена или истекла");
        }

        try {
            String answersJson = mapper.writeValueAsString(request.answers());
            var result = generationService.answer(session.markdown(), session.testCasesJson(), answersJson);

            // Перезаписываем MD-файлы обновлёнными версиями
            List<GeneratedFile> files = persist(result.testCases(), result.clarifications());
            String status = result.clarifications().isEmpty() ? "DONE" : "NEEDS_CLARIFICATION";

            if ("DONE".equals(status)) {
                sessionStore.remove(taskId);
            } else {
                String json = mapper.writeValueAsString(result.testCases());
                sessionStore.put(taskId, session.markdown(), json);
            }

            return ResponseEntity.ok(new GenerateResponse(taskId, status,
                    result.testCases(), result.clarifications(), files));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка обработки ответов", e);
        }
    }

    private String resolveMarkdown(GenerateRequest request) {
        if (request.hasMarkdown()) {
            return request.markdown();
        }
        if (request.hasPdfToMdUrl()) {
            return pdfToMdClient.fetchMarkdown(request.pdfToMdUrl());
        }
        throw new IllegalArgumentException("Нужно передать либо markdown, либо pdfToMdUrl");
    }

    private List<GeneratedFile> persist(List<TestCase> testCases, List<Clarification> clarifications) {
        List<GeneratedFile> files = new ArrayList<>();
        if (testCases != null) {
            for (TestCase tc : testCases) {
                String filename = renderer.fileNameFor(tc);
                String content = renderer.render(tc, clarifications);
                var saved = storage.save(filename, content);
                files.add(new GeneratedFile(tc.id(), saved.filename(), saved.downloadUrl()));
            }
        }
        if (clarifications != null && !clarifications.isEmpty()) {
            String name = "_clarifications.md";
            var saved = storage.save(name, renderer.renderClarifications(clarifications));
            files.add(new GeneratedFile("_clarifications", saved.filename(), saved.downloadUrl()));
        }
        if (testCases != null && !testCases.isEmpty()) {
            String name = "_index.md";
            var saved = storage.save(name, renderer.renderIndex(testCases, clarifications));
            files.add(new GeneratedFile("_index", saved.filename(), saved.downloadUrl()));
        }
        return files;
    }
}
