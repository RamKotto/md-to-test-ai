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
        PersistResult persisted = persist(result.testCases(), result.clarifications(), prefix);
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
                result.testCases(), result.clarifications(), persisted.files()));
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
            var result = generationService.answer(
                    session.markdown(), session.testCasesJson(), answersJson);

            PersistResult persisted = persist(result.testCases(), result.clarifications(),
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
                    result.testCases(), result.clarifications(), persisted.files()));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка обработки ответов", e);
        }
    }

    private boolean hasMissingRequirements(List<Clarification> clarifications) {
        if (clarifications == null || clarifications.isEmpty()) return false;
        return clarifications.stream()
                .anyMatch(c -> c.type() == null
                        || c.type() == ClarificationType.MISSING_REQUIREMENT);
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

    /**
     * Результат persist: files[] + data для _index.md.
     */
    private record PersistResult(List<GeneratedFile> files) {
    }

    /**
     * FIX (index desync): _index.md строится из РЕАЛЬНЫХ имён сохранённых файлов,
     * а не из fileNameFor(tc). Иначе при коллизии имён (TC-X.md уже есть)
     * storage переименует файл в TC-X_2.md, а индекс продолжит ссылаться на TC-X.md.
     */
    private PersistResult persist(List<TestCase> testCases,
                                  List<Clarification> clarifications,
                                  String prefix) {
        List<TestStorageService.PendingFile> pending = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        // Для индекса: testCaseId → реальное имя файла (заполняется после saveAll)
        LinkedHashMap<String, TestCase> idToTc = new LinkedHashMap<>();
        List<String> pendingIds = new ArrayList<>();

        Map<String, Integer> nameCount = new HashMap<>();

        if (testCases != null) {
            Set<String> seenIds = new HashSet<>();
            for (TestCase tc : testCases) {
                if (!seenIds.add(tc.id())) {
                    log.warn("Дубликат id тест-кейса: {}", tc.id());
                }
                String baseName = prefix + renderer.fileNameFor(tc);
                String uniqueName = uniquify(baseName, nameCount);
                pending.add(new TestStorageService.PendingFile(
                        uniqueName, renderer.render(tc, clarifications)));
                pendingIds.add(tc.id());
                idToTc.put(tc.id(), tc);
            }
        }

        boolean hasMissing = clarifications != null && clarifications.stream()
                .anyMatch(c -> c.type() == null || c.type() == ClarificationType.MISSING_REQUIREMENT);
        String clarFilename = null;
        if (hasMissing) {
            clarFilename = uniquify(prefix + "_clarifications.md", nameCount);
            pending.add(new TestStorageService.PendingFile(
                    clarFilename, renderer.renderClarifications(clarifications)));
        }

        // Сначала сохраняем testCases + clarifications, чтобы получить реальные имена.
        var saved = storage.saveAll(pending);

        // Собираем карту testCaseId → SavedFile по реальным именам.
        Map<String, TestStorageService.SavedFile> savedById = new LinkedHashMap<>();
        for (int i = 0; i < pendingIds.size(); i++) {
            savedById.put(pendingIds.get(i), saved.get(i));
        }

        // Теперь рендерим индекс из РЕАЛЬНЫХ имён и сохраняем отдельно.
        List<GeneratedFile> files = new ArrayList<>(saved.size() + 1);
        for (var sf : saved) {
            files.add(new GeneratedFile(
                    matchId(sf.filename(), pendingIds, savedById),
                    sf.filename(), sf.downloadUrl()));
        }

        if (testCases != null && !testCases.isEmpty()) {
            String idxName = uniquify(prefix + "_index.md", nameCount);
            String indexMd = renderer.renderIndexFromSaved(testCases, clarifications,
                    savedById, clarFilename);
            var idxSaved = storage.save(idxName, indexMd);
            files.add(new GeneratedFile("_index", idxSaved.filename(), idxSaved.downloadUrl()));
        }

        return new PersistResult(files);
    }

    private String matchId(String filename,
                           List<String> pendingIds,
                           Map<String, TestStorageService.SavedFile> savedById) {
        for (var e : savedById.entrySet()) {
            if (e.getValue().filename().equals(filename)) return e.getKey();
        }
        if (filename.endsWith("_clarifications.md")) return "_clarifications";
        return "_index";
    }

    private String uniquify(String base, Map<String, Integer> nameCount) {
        int n = nameCount.merge(base, 1, Integer::sum);
        if (n == 1) return base;
        int dot = base.lastIndexOf('.');
        if (dot < 0) return base + "_" + (n - 1);
        return base.substring(0, dot) + "_" + (n - 1) + base.substring(dot);
    }

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
