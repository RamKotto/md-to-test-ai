package application.controller;

import application.dto.Dtos.*;
import application.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

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

        String prefix = sanitizePrefix(request.filename());
        List<GeneratedFile> files = persist(result.testCases(), result.clarifications(), prefix);
        String status = hasMissingRequirements(result.clarifications())
                ? "NEEDS_CLARIFICATION" : "DONE";

        if ("NEEDS_CLARIFICATION".equals(status)) {
            try {
                String json = mapper.writeValueAsString(result.testCases());
                sessionStore.put(taskId, markdown, json, prefix);
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
            // answer() внутри не применяет санитизацию по документу: ответы — источник истины.
            var result = generationService.answer(
                    session.markdown(), session.testCasesJson(), answersJson);

            List<GeneratedFile> files = persist(result.testCases(), result.clarifications(),
                    session.filenamePrefix());
            String status = hasMissingRequirements(result.clarifications())
                    ? "NEEDS_CLARIFICATION" : "DONE";

            if ("DONE".equals(status)) {
                sessionStore.remove(taskId);
            } else {
                String json = mapper.writeValueAsString(result.testCases());
                sessionStore.put(taskId, session.markdown(), json, session.filenamePrefix());
            }

            return ResponseEntity.ok(new GenerateResponse(taskId, status,
                    result.testCases(), result.clarifications(), files));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка обработки ответов", e);
        }
    }

    /**
     * NEEDS_CLARIFICATION только если есть настоящие вопросы пользователю.
     * UNGROUNDED_ASSUMPTION — это сигнал о качестве генерации, он не требует
     * возврата к пользователю и не должен блокировать DONE.
     */
    private boolean hasMissingRequirements(List<Clarification> clarifications) {
        if (clarifications == null || clarifications.isEmpty()) return false;
        return clarifications.stream()
                .anyMatch(c -> c.type() == ClarificationType.MISSING_REQUIREMENT);
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

    private List<GeneratedFile> persist(List<TestCase> testCases,
                                        List<Clarification> clarifications,
                                        String prefix) {
        List<TestStorageService.PendingFile> pending = new ArrayList<>();
        List<String> ids = new ArrayList<>();

        if (testCases != null) {
            Set<String> seenIds = new HashSet<>();
            for (TestCase tc : testCases) {
                if (!seenIds.add(tc.id())) {
                    log.warn("Дубликат id тест-кейса, файл будет переименован: {}", tc.id());
                }
                pending.add(new TestStorageService.PendingFile(
                        prefix + renderer.fileNameFor(tc),
                        renderer.render(tc, clarifications)));
                ids.add(tc.id());
            }
        }
        // _clarifications.md пишем, только если есть настоящие вопросы пользователю.
        if (clarifications != null && clarifications.stream()
                .anyMatch(c -> c.type() == ClarificationType.MISSING_REQUIREMENT)) {
            pending.add(new TestStorageService.PendingFile(
                    prefix + "_clarifications.md",
                    renderer.renderClarifications(clarifications)));
            ids.add("_clarifications");
        }
        if (testCases != null && !testCases.isEmpty()) {
            pending.add(new TestStorageService.PendingFile(
                    prefix + "_index.md",
                    renderer.renderIndex(testCases, clarifications)));
            ids.add("_index");
        }

        var saved = storage.saveAll(pending);
        List<GeneratedFile> files = new ArrayList<>(saved.size());
        for (int i = 0; i < saved.size(); i++) {
            var s = saved.get(i);
            files.add(new GeneratedFile(ids.get(i), s.filename(), s.downloadUrl()));
        }
        return files;
    }

    /**
     * Приводит filename к безопасному префиксу для имени файла.
     * "swagger.yaml" -> "swagger_", "" / null -> "".
     */
    private String sanitizePrefix(String filename) {
        if (filename == null || filename.isBlank()) return "";
        String base = filename;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String cleaned = base.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        if (cleaned.isBlank()) return "";
        if (cleaned.length() > 40) cleaned = cleaned.substring(0, 40);
        return cleaned + "_";
    }
}
