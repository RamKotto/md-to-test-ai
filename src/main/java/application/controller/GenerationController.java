package application.controller;

import application.dto.Dtos.*;
import application.exception.GenerationException;
import application.exception.StorageException;
import application.repository.GenerationRepository;
import application.service.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST-контроллер генерации тест-кейсов ({@code /api/generate}).
 * <p>
 * Оркестрирует весь прогон: получение Markdown (из тела запроса или через {@link PdfToMdClient}),
 * генерацию и проверку через {@link SyncGenerationService}, рендер и атомарную запись файлов через
 * {@link TestStorageService}, хранение сессии уточнений в {@link SessionStore} и снимок прогона
 * в БД через {@link GenerationRepository}.
 * <p>
 * Статус прогона: {@code DONE}, если нет уточнений типа {@code MISSING_REQUIREMENT};
 * {@code NEEDS_CLARIFICATION} — иначе. Файлы создаются при любом статусе.
 */
@Slf4j
@RestController
@RequestMapping("/api/generate")
@RequiredArgsConstructor
public class GenerationController {

    /** Статус прогона без открытых вопросов к пользователю. */
    private static final String STATUS_DONE = "DONE";

    /** Статус прогона, в котором есть уточнения, требующие ответа; для него создаётся сессия. */
    private static final String STATUS_NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION";

    /**
     * Служебный ключ в карте filesById для файла индекса {@code _index.md}.
     * Не совпадает с id тест-кейсов, так как id тест-кейса всегда имеет вид TC-...
     */
    private static final String KEY_INDEX = "_index";

    /** Служебный ключ в карте filesById для файла уточнений {@code _clarifications.md}. */
    private static final String KEY_CLARIFICATIONS = "_clarifications";

    /** Суффикс имени файла индекса; итоговое имя — префикс и суффикс. */
    private static final String INDEX_SUFFIX = "_index.md";

    /** Суффикс имени файла уточнений; итоговое имя — префикс и суффикс. */
    private static final String CLARIFICATIONS_SUFFIX = "_clarifications.md";

    private final SyncGenerationService generationService;
    private final TestCaseMarkdownRenderer renderer;
    private final TestStorageService storage;
    private final PdfToMdClient pdfToMdClient;
    private final SessionStore sessionStore;
    private final GenerationRepository repository;
    private final ObjectMapper mapper;

    /**
     * Первичная генерация тест-кейсов.
     * <p>
     * Порядок шагов: получение Markdown (приоритет у {@code markdown}, иначе скачивание по
     * {@code pdfToMdUrl}); генерация и проверка через LLM; при статусе {@code NEEDS_CLARIFICATION}
     * проверка размера документа для сессии (до записи файлов); запись файлов; сохранение сессии;
     * пересохранение снимка прогона в БД. Если проверка размера не проходит, файлы не записываются.
     *
     * @param request тело запроса; должен быть задан {@code markdown} или {@code pdfToMdUrl}
     * @return {@code 200 OK} с {@link GenerateResponse}: {@code taskId}, статус, тест-кейсы,
     *         уточнения и список записанных файлов
     * @throws IllegalArgumentException если не передано ни одного непустого источника, документ
     *                                  слишком велик для сессии, либо {@code pdfToMdUrl} некорректен
     *                                  или его хост не разрешён (ответ {@code 400})
     * @throws GenerationException      при ошибке OpenRouter или невалидном ответе модели (ответ {@code 502})
     * @throws PdfToMdClientException   при ошибке скачивания Markdown из pdf-to-md (ответ {@code 502})
     * @throws StorageException         при ошибке записи файлов (ответ {@code 500})
     */
    @PostMapping
    public ResponseEntity<GenerateResponse> generate(@RequestBody GenerateRequest request) {
        String markdown = resolveMarkdown(request);
        if (markdown.isBlank()) {
            throw new IllegalArgumentException("Markdown не может быть пустым");
        }

        var result = generationService.generate(markdown);
        String taskId = UUID.randomUUID().toString();
        String prefix = sanitizePrefix(request.filename());
        String status = statusOf(result.clarifications());
        boolean needsSession = STATUS_NEEDS_CLARIFICATION.equals(status);

        String sessionJson = null;
        if (needsSession) {
            sessionStore.checkSize(markdown);
            sessionJson = toJson(result.testCases());
        }

        PersistResult persisted = persist(result.testCases(), result.clarifications(), prefix);

        if (needsSession) {
            sessionStore.put(taskId, markdown, sessionJson, prefix, persisted.filesById());
        }
        repository.replaceRun(UUID.fromString(taskId), status, prefix, markdown,
                result.testCases(), persisted.filesById(), result.clarifications());

        return ResponseEntity.ok(new GenerateResponse(taskId, status,
                result.testCases(), result.clarifications(), persisted.files()));
    }

    /**
     * Обработка ответов пользователя на уточнения по существующему прогону.
     * <p>
     * Сессия берётся из {@link SessionStore}. Далее модель получает исходный документ, текущие
     * тест-кейсы и ответы. Имена файлов для тех же id переиспользуются (файлы перезаписываются),
     * новые имена уникализируются. Файлы прошлого прогона, которых нет в новом наборе, удаляются.
     * Запись файлов атомарна (см. {@link TestStorageService#replaceAll}). После этого снимок в БД
     * перезаписывается, а сессия обновляется (при {@code NEEDS_CLARIFICATION}) или удаляется (при {@code DONE}).
     * <p>
     * Ошибки, кроме доменных ({@link IllegalArgumentException}, {@link GenerationException},
     * {@link StorageException}), оборачиваются в {@link IllegalStateException} и дают ответ {@code 500}.
     *
     * @param taskId  идентификатор прогона из ответа {@code /api/generate}
     * @param request тело с ответами пользователя; список {@code answers} не валидируется
     * @return {@code 200 OK} с {@link GenerateResponse} в том же формате, что и первичная генерация
     * @throws IllegalArgumentException если сессия не найдена или истекла, либо имя файла некорректно
     *                                  (ответ {@code 400})
     * @throws GenerationException      при ошибке OpenRouter или невалидном ответе модели (ответ {@code 502})
     * @throws StorageException         при ошибке записи файлов (ответ {@code 500})
     */
    @PostMapping("/{taskId}/answers")
    public ResponseEntity<GenerateResponse> answer(@PathVariable String taskId,
                                                   @RequestBody AnswerRequest request) {
        SessionStore.Session session = sessionStore.get(taskId);
        if (session == null) {
            throw new IllegalArgumentException("Сессия не найдена или истекла");
        }

        try {
            String answersJson = toJson(request.answers());
            var result = generationService.answer(
                    session.markdown(), session.testCasesJson(), answersJson);

            String status = statusOf(result.clarifications());
            boolean needsSession = STATUS_NEEDS_CLARIFICATION.equals(status);
            String sessionJson = needsSession ? toJson(result.testCases()) : null;

            List<TestCase> cases = nullToEmpty(result.testCases());

            Map<String, String> filesById = planNames(cases, result.clarifications(),
                    session.filenamePrefix(), session.filesById());
            List<TestStorageService.PendingFile> pending =
                    renderPending(filesById, cases, result.clarifications());

            Set<String> newNames = new HashSet<>(filesById.values());
            List<String> toDelete = session.filesById().values().stream()
                    .filter(name -> !newNames.contains(name))
                    .distinct()
                    .toList();

            List<TestStorageService.SavedFile> saved = storage.replaceAll(pending, toDelete);

            List<String> ids = new ArrayList<>(filesById.keySet());
            List<GeneratedFile> files = new ArrayList<>(saved.size());
            for (int i = 0; i < saved.size(); i++) {
                var sf = saved.get(i);
                files.add(new GeneratedFile(ids.get(i), sf.filename(), sf.downloadUrl()));
            }

            repository.replaceRun(UUID.fromString(taskId), status, session.filenamePrefix(),
                    session.markdown(), cases, filesById, result.clarifications());

            if (needsSession) {
                sessionStore.put(taskId, session.markdown(), sessionJson,
                        session.filenamePrefix(), filesById);
            } else {
                sessionStore.remove(taskId);
            }

            return ResponseEntity.ok(new GenerateResponse(taskId, status,
                    result.testCases(), result.clarifications(), files));
        } catch (IllegalArgumentException | GenerationException | StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка обработки ответов", e);
        }
    }

    /**
     * Результат {@link #persist}: список файлов для ответа клиенту и карта id → имя файла
     * для сессии и БД.
     *
     * @param files     записанные файлы в порядке записи (тест-кейсы, уточнения, индекс)
     * @param filesById служебный ключ или id тест-кейса → имя файла на диске
     */
    private record PersistResult(List<GeneratedFile> files, Map<String, String> filesById) {
    }

    /**
     * Первичная запись файлов прогона.
     * <p>
     * Файлы тест-кейсов и {@code _clarifications.md} (если есть уточнения типа
     * {@code MISSING_REQUIREMENT}) записываются через {@link TestStorageService#saveAll}, где имена
     * уникализируются относительно диска. Затем по фактическим именам строится и сохраняется
     * {@code _index.md}, но только если есть тест-кейсы. Если сохранение индекса не удалось,
     * уже записанные файлы откатываются, и исключение пробрасывается дальше.
     *
     * @param testCases      тест-кейсы прогона (может быть {@code null})
     * @param clarifications уточнения прогона (может быть {@code null})
     * @param prefix         префикс имён файлов, может быть пустым
     * @return список записанных файлов и карта id → имя файла
     * @throws StorageException если запись файлов завершилась ошибкой
     */
    private PersistResult persist(List<TestCase> testCases,
                                  List<Clarification> clarifications,
                                  String prefix) {
        List<TestCase> cases = nullToEmpty(testCases);

        List<TestStorageService.PendingFile> pending = new ArrayList<>();
        List<String> pendingIds = new ArrayList<>();
        for (TestCase tc : cases) {
            pending.add(new TestStorageService.PendingFile(
                    prefix + renderer.fileNameFor(tc), renderer.render(tc, clarifications)));
            pendingIds.add(tc.id());
        }
        if (hasMissingRequirements(clarifications)) {
            pending.add(new TestStorageService.PendingFile(
                    prefix + CLARIFICATIONS_SUFFIX, renderer.renderClarifications(clarifications)));
            pendingIds.add(KEY_CLARIFICATIONS);
        }

        List<TestStorageService.SavedFile> saved = storage.saveAll(pending);

        Map<String, String> filesById = new LinkedHashMap<>();
        List<GeneratedFile> files = new ArrayList<>(saved.size() + 1);
        for (int i = 0; i < saved.size(); i++) {
            var sf = saved.get(i);
            filesById.put(pendingIds.get(i), sf.filename());
            files.add(new GeneratedFile(pendingIds.get(i), sf.filename(), sf.downloadUrl()));
        }

        if (!cases.isEmpty()) {
            String indexMd = renderer.renderIndexFromSaved(cases, clarifications,
                    filesById, filesById.get(KEY_CLARIFICATIONS));
            TestStorageService.SavedFile idx;
            try {
                idx = storage.save(prefix + INDEX_SUFFIX, indexMd);
            } catch (RuntimeException e) {
                rollbackCreated(saved);
                throw e;
            }
            filesById.put(KEY_INDEX, idx.filename());
            files.add(new GeneratedFile(KEY_INDEX, idx.filename(), idx.downloadUrl()));
        }

        return new PersistResult(files, filesById);
    }

    /**
     * Планирует имена файлов для ответа на уточнения.
     * <p>
     * Порядок ключей результата: тест-кейсы, {@code _clarifications} (если нужен), {@code _index}
     * (если есть тест-кейсы). Для id, уже записанных в прошлом прогоне, имя сохраняется, чтобы файл
     * был перезаписан. Остальным даётся свободное имя через {@link #freshName}. Повторно используемые
     * имена резервируются до назначения новых, поэтому новые их не занимают.
     *
     * @param cases          тест-кейсы нового прогона
     * @param clarifications уточнения нового прогона
     * @param prefix         префикс имён файлов сессии
     * @param previous       карта id → имя файла из предыдущего прогона сессии
     * @return карта id → запланированное имя файла (без записи на диск)
     */
    private Map<String, String> planNames(List<TestCase> cases,
                                          List<Clarification> clarifications,
                                          String prefix,
                                          Map<String, String> previous) {
        List<String> ids = new ArrayList<>();
        List<String> preferred = new ArrayList<>();
        List<String> bases = new ArrayList<>();

        for (TestCase tc : cases) {
            ids.add(tc.id());
            preferred.add(previous.get(tc.id()));
            bases.add(prefix + renderer.fileNameFor(tc));
        }
        if (hasMissingRequirements(clarifications)) {
            ids.add(KEY_CLARIFICATIONS);
            preferred.add(previous.get(KEY_CLARIFICATIONS));
            bases.add(prefix + CLARIFICATIONS_SUFFIX);
        }
        if (!cases.isEmpty()) {
            ids.add(KEY_INDEX);
            preferred.add(previous.get(KEY_INDEX));
            bases.add(prefix + INDEX_SUFFIX);
        }

        String[] names = new String[ids.size()];
        Set<String> used = new HashSet<>();
        for (int i = 0; i < names.length; i++) {
            String p = preferred.get(i);
            if (p != null && used.add(p)) {
                names[i] = p;
            }
        }
        for (int i = 0; i < names.length; i++) {
            if (names[i] == null) {
                names[i] = freshName(bases.get(i), used, previous.values());
            }
        }

        Map<String, String> filesById = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            filesById.put(ids.get(i), names[i]);
        }
        return filesById;
    }

    /**
     * Рендерит содержимое файлов по запланированным именам.
     * <p>
     * Для {@code _index} используется фактическое содержимое {@link TestCaseMarkdownRenderer#renderIndexFromSaved}
     * с итоговыми именами файлов. Для тест-кейса берётся {@code TestCase} по его id.
     *
     * @param filesById      карта id → имя файла из {@link #planNames}
     * @param cases          тест-кейсы прогона
     * @param clarifications уточнения прогона
     * @return список {@link TestStorageService.PendingFile} в порядке {@code filesById}
     */
    private List<TestStorageService.PendingFile> renderPending(Map<String, String> filesById,
                                                               List<TestCase> cases,
                                                               List<Clarification> clarifications) {
        Map<String, TestCase> byId = new HashMap<>();
        for (TestCase tc : cases) {
            byId.putIfAbsent(tc.id(), tc);
        }
        List<TestStorageService.PendingFile> pending = new ArrayList<>(filesById.size());
        for (Map.Entry<String, String> e : filesById.entrySet()) {
            String content = switch (e.getKey()) {
                case KEY_INDEX -> renderer.renderIndexFromSaved(cases, clarifications,
                        filesById, filesById.get(KEY_CLARIFICATIONS));
                case KEY_CLARIFICATIONS -> renderer.renderClarifications(clarifications);
                default -> renderer.render(byId.get(e.getKey()), clarifications);
            };
            pending.add(new TestStorageService.PendingFile(e.getValue(), content));
        }
        return pending;
    }

    /**
     * Подбирает свободное имя файла на основе {@code base}.
     * <p>
     * Имя считается занятым, если оно уже в {@code used} (текущий батч) или существует на диске
     * и не входит в {@code ownPrevious}. Имена из {@code ownPrevious} считаются свободными, потому
     * что файлы этого прогона будут либо перезаписаны, либо удалены. Иначе перебираются варианты
     * {@code base_2.md}, {@code base_3.md} и так далее. Найденное имя добавляется в {@code used}.
     *
     * @param base        базовое имя файла вида {@code <prefix><ID>_<slug>.md} или служебное
     * @param used        имена, уже назначенные в текущем батче; изменяется методом
     * @param ownPrevious имена файлов предыдущего прогона этой сессии
     * @return имя, которое не занято в батче и не принадлежит чужому файлу на диске
     */
    private String freshName(String base, Set<String> used, Collection<String> ownPrevious) {
        String stem = base.substring(0, base.length() - ".md".length());
        String candidate = base;
        for (int i = 2; used.contains(candidate) || isForeignOnDisk(candidate, ownPrevious); i++) {
            candidate = stem + "_" + i + ".md";
        }
        used.add(candidate);
        return candidate;
    }

    /**
     * Проверяет, занято ли имя файлом, не принадлежащим текущему прогону.
     *
     * @param name         проверяемое имя файла
     * @param ownPrevious  имена файлов текущего прогона
     * @return {@code true}, если имя не из {@code ownPrevious} и файл с таким именем есть в хранилище
     */
    private boolean isForeignOnDisk(String name, Collection<String> ownPrevious) {
        return !ownPrevious.contains(name) && storage.exists(name);
    }

    /**
     * Откатывает файлы, созданные в текущем вызове {@link #persist}. Удаление выполняется через
     * {@link TestStorageService#replaceAll} с пустым набором файлов. Ошибка отката логируется
     * и не пробрасывается, чтобы не подменить исходную ошибку.
     *
     * @param created файлы, которые были записаны до сбоя
     */
    private void rollbackCreated(List<TestStorageService.SavedFile> created) {
        List<String> names = created.stream().map(TestStorageService.SavedFile::filename).toList();
        try {
            storage.replaceAll(List.of(), names);
        } catch (RuntimeException e) {
            log.error("Не удалось откатить созданные файлы {}", names, e);
        }
    }

    /**
     * Возвращает статус прогона по списку уточнений.
     *
     * @param clarifications уточнения прогона
     * @return {@code NEEDS_CLARIFICATION}, если есть уточнение, требующее ответа; иначе {@code DONE}
     */
    private String statusOf(List<Clarification> clarifications) {
        return hasMissingRequirements(clarifications) ? STATUS_NEEDS_CLARIFICATION : STATUS_DONE;
    }

    /**
     * Определяет, есть ли уточнения, требующие ответа пользователя.
     * Уточнение с типом {@code UNGROUNDED_ASSUMPTION} не учитывается; уточнение без типа считается
     * {@code MISSING_REQUIREMENT}.
     *
     * @param clarifications уточнения прогона; может быть {@code null}
     * @return {@code true}, если найдено хотя бы одно такое уточнение
     */
    private boolean hasMissingRequirements(List<Clarification> clarifications) {
        if (clarifications == null || clarifications.isEmpty()) return false;
        return clarifications.stream()
                .anyMatch(c -> c.type() == null
                        || c.type() == ClarificationType.MISSING_REQUIREMENT);
    }

    /**
     * Выбирает источник Markdown: непустой {@code markdown} имеет приоритет, иначе документ
     * скачивается по {@code pdfToMdUrl}.
     *
     * @param request тело запроса
     * @return Markdown-текст для генерации
     * @throws IllegalArgumentException если ни одно поле не содержит непустого значения
     */
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
     * Сериализует значение в JSON для передачи в LLM и в сессию.
     *
     * @param value объект для сериализации
     * @return JSON-строка
     * @throws IllegalStateException если сериализация не удалась (ответ {@code 500})
     */
    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Ошибка сериализации", e);
        }
    }

    /**
     * Заменяет {@code null} на пустой список.
     *
     * @param list исходный список, может быть {@code null}
     * @param <T>  тип элементов
     * @return {@code list} или неизменяемый пустой список, если {@code list} равен {@code null}
     */
    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    /**
     * Строит префикс имён файлов из имени исходного документа.
     * <p>
     * Расширение отбрасывается (точка учитывается, только если она не первый символ). Символы вне
     * {@code [a-zA-Z0-9_-]} заменяются на {@code _}. Результат обрезается до 40 символов и
     * дополняется {@code _}. Для пустого или некорректного имени возвращается пустая строка.
     *
     * @param filename имя исходного документа из запроса, может быть {@code null}
     * @return префикс вида {@code document_} или пустая строка
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
