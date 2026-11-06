package application.service;

import application.config.AppProperties;
import application.dto.Dtos.AnswerRequest;
import application.dto.Dtos.ClarificationType;
import application.dto.Dtos.Clarification;
import application.dto.Dtos.GenerateRequest;
import application.dto.Dtos.GenerateResponse;
import application.dto.Dtos.GeneratedFile;
import application.dto.Dtos.TestCase;
import application.repository.ArtifactRepository;
import application.repository.ArtifactRepository.NewArtifact;
import application.repository.ClarificationSessionRepository;
import application.repository.ClarificationSessionRepository.AnswerRow;
import application.repository.ClarificationSessionRepository.SessionRow;
import application.repository.GenerationRepository;
import application.repository.JobRepository.JobRow;
import application.service.AnswerMerger.MergedAnswer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Оркестрация генерации тест-кейсов и уточнений: первичная генерация, ответы пользователя,
 * чтение сессии и сохранение черновика.
 * <p>
 * Все артефакты и сессия пишутся в БД ({@code qa.artifacts}, {@code qa.clarification_sessions},
 * {@code qa.clarification_answers}). Запись прогона выполняется в одной транзакции: при сбое любого шага
 * (включая вызов модели в ответе на уточнения) БД остаётся в прежнем состоянии.
 * <p>
 * Долгие операции ({@link #generate} и {@link #answer}) асинхронные: метод проверяет вход, создаёт или переводит
 * задачу в {@code PROCESSING} ({@code qa.jobs}) и возвращает состояние (202). Вызов модели, запись файлов и сессии
 * выполняются в фоне через {@link JobService}; результат фиксируется статусом задачи {@code DONE} или {@code FAILED}.
 * Статус {@code DONE} задачи пишется в той же транзакции, что и результат, поэтому они согласованы.
 */
@Service
@RequiredArgsConstructor
public class GenerationFlowService {

    /** Статус прогона без открытых вопросов к пользователю. */
    static final String STATUS_DONE = "DONE";

    /** Статус прогона, в котором есть уточнения, требующие ответа. */
    static final String STATUS_NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION";

    /** Текст ошибки для отсутствующей или истёкшей сессии. */
    static final String SESSION_NOT_FOUND = "Сессия не найдена или истекла";

    /** Служебный id файла индекса {@code _index.md}; не совпадает с id тест-кейсов (TC-...). */
    static final String KEY_INDEX = "_index";

    /** Служебный id файла уточнений {@code _clarifications.md}. */
    static final String KEY_CLARIFICATIONS = "_clarifications";

    /** Базовое имя индекса без префикса. */
    private static final String INDEX_BASENAME = "index.md";

    /** Базовое имя файла уточнений без префикса. */
    private static final String CLARIFICATIONS_BASENAME = "clarifications.md";

    /** Вид артефакта: тест-кейс. */
    static final String KIND_TEST_CASE = "test_case";

    /** Вид артефакта: индекс прогона. */
    static final String KIND_INDEX = "index";

    /** Вид артефакта: прочий Markdown (файл уточнений). */
    static final String KIND_MARKDOWN = "markdown";

    /** MIME-тип файлов прогона. */
    static final String MARKDOWN_TYPE = "text/markdown; charset=UTF-8";

    private final SyncGenerationService generationService;
    private final TestCaseMarkdownRenderer renderer;
    private final PdfToMdClient pdfToMdClient;
    private final AppProperties props;
    private final ArtifactRepository artifacts;
    private final ClarificationSessionRepository sessions;
    private final GenerationRepository repository;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final JobService jobs;

    /**
     * Состояние сессии в {@code qa.clarification_sessions.state}.
     *
     * @param markdown       исходный Markdown
     * @param testCasesJson  JSON текущих тест-кейсов
     * @param filenamePrefix префикс имён файлов
     * @param filesById      id → имя файла прогона
     * @param lastResponse   последний ответ POST в виде JSON
     */
    record SessionState(String markdown, String testCasesJson, String filenamePrefix,
                        Map<String, String> filesById, JsonNode lastResponse) {
    }

    /**
     * Файл, отрендеренный для записи.
     *
     * @param id       логический id (id тест-кейса, {@link #KEY_INDEX} или {@link #KEY_CLARIFICATIONS})
     * @param kind     вид артефакта
     * @param filename имя файла
     * @param content  содержимое Markdown
     */
    private record Rendered(String id, String kind, String filename, String content) {
    }

    /**
     * Запускает первичную генерацию тест-кейсов в фоне и сразу возвращает состояние задачи.
     * <p>
     * Синхронно: проверяются источник ({@code markdown} или {@code pdfToMdUrl} по allowlist) и размер переданного
     * Markdown; вычисляется префикс имён; создаются {@code taskId} и задача {@code PROCESSING}.
     * В фоне ({@link #runGenerate}): скачивание из pdf-to-md (если нужно), генерация, запись файлов и сессии
     * (для любого статуса), задача {@code DONE}. Ошибки фона попадают в задачу как {@code FAILED}.
     *
     * @param request тело запроса; должен быть задан {@code markdown} или {@code pdfToMdUrl}
     * @return состояние задачи: {@code jobStatus = PROCESSING}, {@code status = null}, {@code version = 0}
     * @throws IllegalArgumentException источник не задан, URL некорректен или документ слишком велик (ответ {@code 400})
     */
    public ObjectNode generate(GenerateRequest request) {
        validateSource(request);
        String prefix = sanitizePrefix(request.filename());
        UUID taskId = UUID.randomUUID();
        JobRow job = jobs.create(taskId);
        jobs.runInBackground(taskId, () -> runGenerate(taskId, request, prefix));
        return state(taskId, job, null);
    }

    /**
     * Синхронная проверка источника первичной генерации. Скачивание из pdf-to-md здесь не выполняется.
     *
     * @param request тело запроса
     * @throws IllegalArgumentException если ни одно поле не задано, URL некорректен или markdown слишком велик
     */
    private void validateSource(GenerateRequest request) {
        if (request.hasMarkdown()) {
            checkSize(request.markdown());
        } else if (request.hasPdfToMdUrl()) {
            pdfToMdClient.validateUrl(request.pdfToMdUrl());
        } else {
            throw new IllegalArgumentException("Нужно передать либо markdown, либо pdfToMdUrl");
        }
    }

    /**
     * Фоновая часть первичной генерации. Файлы, сессия и статус {@code DONE} записываются одной транзакцией.
     * Исключения не перехватываются: их переводит в {@code FAILED} {@link JobService}.
     *
     * @param taskId  идентификатор задачи и прогона
     * @param request тело запроса
     * @param prefix  префикс имён файлов
     */
    private void runGenerate(UUID taskId, GenerateRequest request, String prefix) {
        String markdown = resolveMarkdown(request);
        checkSize(markdown);
        var result = generationService.generate(markdown);

        List<TestCase> cases = nullToEmpty(result.testCases());
        List<Clarification> clarifications = nullToEmpty(result.clarifications());
        String status = statusOf(clarifications);
        Map<String, String> filesById = planNames(cases, clarifications, prefix, Map.of());

        tx.executeWithoutResult(s -> {
            GenerateResponse response = commitRun(taskId, status, prefix, markdown, cases, clarifications,
                    filesById, List.of(), 1);
            // Сессия создаётся при любом статусе: результат доступен через GET после перезагрузки страницы
            sessions.insert(taskId, status,
                    toJson(new SessionState(markdown, toJson(cases), prefix, filesById,
                            mapper.valueToTree(response))),
                    toJson(clarifications));
            jobs.markDone(taskId);
        });
    }

    /**
     * Принимает ответы пользователя на уточнения и запускает обработку в фоне.
     * <p>
     * Синхронно: сессия существует и не истекла; её статус {@code NEEDS_CLARIFICATION}; {@code expectedVersion}
     * совпадает с версией; задача переходит в {@code PROCESSING} условным UPDATE (если уже выполняется, ответ 409).
     * В фоне ({@link #runAnswer}): повторная проверка под блокировкой строки, модель, файлы, сессия и
     * задача {@code DONE} в одной транзакции.
     *
     * @param taskId  идентификатор прогона
     * @param request ответы и необязательная версия
     * @return состояние задачи (данные последней успешной версии) с {@code jobStatus = PROCESSING}
     * @throws IllegalArgumentException сессия не найдена или истекла, либо уже завершена (ответ {@code 400})
     * @throws application.exception.ConflictException версия не совпадает или операция уже выполняется (ответ {@code 409})
     */
    public ObjectNode answer(String taskId, AnswerRequest request) {
        UUID id = parseSessionId(taskId);
        SessionRow row = sessions.findActive(id)
                .orElseThrow(() -> new IllegalArgumentException(SESSION_NOT_FOUND));
        // Сессия DONE — только для просмотра истории: повторная генерация по ней не запускается
        if (!STATUS_NEEDS_CLARIFICATION.equals(row.status())) {
            throw new IllegalArgumentException("Сессия уже завершена, ответы не принимаются");
        }
        AnswerMerger.checkExpectedVersion(request.expectedVersion(), row.version());

        // Без question ответ не сопоставится с уточнением: все ответы сольются в один ключ дедупликации
        if (request.answers() != null) {
            for (AnswerRequest.Answer a : request.answers()) {
                if (a != null && a.answer() != null && !a.answer().isBlank()
                        && (a.question() == null || a.question().isBlank())) {
                    throw new IllegalArgumentException("Ответ должен содержать question (текст уточнения)");
                }
            }
        }

        JobRow job = jobs.startAgain(id);
        jobs.runInBackground(id, () -> runAnswer(id, request));
        // Сессию перечитываем после перехода в PROCESSING: до него другой ответ мог завершиться
        SessionRow current = sessions.findActive(id).orElse(row);
        return state(id, job, current);
    }

    /**
     * Фоновая часть ответа на уточнения. В одной транзакции с блокировкой строки сессии: повторная проверка
     * статуса и {@code expectedVersion}; запись непустых ответов с {@code round = round + 1}; вызов модели
     * с полным дедуплицированным набором ответов; замена файлов прогона; обновление сессии
     * ({@code version + 1}, {@code round + 1}); задача {@code DONE}. Любой сбой откатывает все шаги,
     * задача переходит в {@code FAILED} (см. {@link JobService}).
     *
     * @param id      идентификатор прогона
     * @param request ответы и необязательная версия
     */
    private void runAnswer(UUID id, AnswerRequest request) {
        tx.executeWithoutResult(s -> {
            SessionRow row = sessions.lockActive(id)
                    .orElseThrow(() -> new IllegalArgumentException(SESSION_NOT_FOUND));
            if (!STATUS_NEEDS_CLARIFICATION.equals(row.status())) {
                throw new IllegalArgumentException("Сессия уже завершена, ответы не принимаются");
            }
            AnswerMerger.checkExpectedVersion(request.expectedVersion(), row.version());

            int round = row.round() + 1;
            List<AnswerRequest.Answer> incoming = request.answers() == null ? List.of() : request.answers();
            for (AnswerRequest.Answer a : incoming) {
                if (a == null || a.answer() == null || a.answer().isBlank()) {
                    continue;
                }
                sessions.insertAnswer(id, round, a.testCaseId(), nz(a.question()), a.topicKey(), a.answer());
            }

            List<MergedAnswer> merged = AnswerMerger.merge(sessions.findAnswers(id));
            SessionState state = readState(row.stateJson());
            var result = generationService.answer(state.markdown(), state.testCasesJson(), toJson(merged));

            List<TestCase> cases = nullToEmpty(result.testCases());
            List<Clarification> clarifications = nullToEmpty(result.clarifications());
            String status = statusOf(clarifications);
            Map<String, String> filesById = planNames(cases, clarifications, state.filenamePrefix(),
                    state.filesById());
            Set<String> newNames = new HashSet<>(filesById.values());
            List<String> stale = state.filesById().values().stream()
                    .filter(name -> !newNames.contains(name))
                    .distinct()
                    .toList();

            int version = row.version() + 1;
            GenerateResponse response = commitRun(id, status, state.filenamePrefix(),
                    state.markdown(), cases, clarifications, filesById, stale, version);
            sessions.updateTurn(id, version, round, status,
                    toJson(new SessionState(state.markdown(), toJson(cases), state.filenamePrefix(), filesById,
                            mapper.valueToTree(response))),
                    toJson(clarifications));
            jobs.markDone(id);
        });
    }

    /**
     * Возвращает состояние задачи и сессии в формате {@code GET /api/generate/{taskId}}: поля задачи
     * ({@code jobStatus}, {@code jobError}, {@code jobStartedAt}) и поля последней успешной версии сессии
     * (последний ответ сервиса, статус, версия, раунд, уточнения, история ответов, черновик, срок хранения).
     * <p>
     * Если сессии ещё нет (первая операция выполняется или упала), возвращается состояние с {@code status = null}
     * и пустыми данными.
     *
     * @param taskId идентификатор прогона
     * @return JSON-объект состояния
     * @throws IllegalArgumentException нет ни задачи, ни сессии (ответ {@code 400})
     */
    public ObjectNode get(String taskId) {
        UUID id = parseSessionId(taskId);
        Optional<JobRow> job = jobs.find(id);
        Optional<SessionRow> session = sessions.findActive(id);
        if (job.isEmpty() && session.isEmpty()) {
            throw new IllegalArgumentException(SESSION_NOT_FOUND);
        }
        return state(id, job.orElse(null), session.orElse(null));
    }

    /**
     * Собирает ответ в формате контракта: сначала поля задачи, затем последний ответ сервиса
     * ({@code state.lastResponse}) и поля сессии. Без сессии данные пустые, {@code status = null}, {@code version = 0}.
     *
     * @param taskId  идентификатор прогона
     * @param job     состояние задачи или {@code null}, если строки задачи нет
     * @param session строка сессии или {@code null}, если сессии нет
     * @return JSON-объект состояния
     */
    private ObjectNode state(UUID taskId, JobRow job, SessionRow session) {
        ObjectNode out = mapper.createObjectNode();
        out.put("taskId", taskId.toString());
        out.put("jobStatus", job == null ? null : job.status());
        out.put("jobError", job == null ? null : job.error());
        out.put("jobStartedAt", job == null ? null : job.startedAt().toString());
        if (session == null) {
            out.putNull("status");
            out.put("version", 0);
            out.put("round", 0);
            out.set("testCases", mapper.createArrayNode());
            out.set("clarifications", mapper.createArrayNode());
            out.set("files", mapper.createArrayNode());
            out.set("answers", mapper.createArrayNode());
            out.set("draft", mapper.createObjectNode());
            out.putNull("expiresAt");
            return out;
        }
        SessionState state = readState(session.stateJson());
        if (state.lastResponse() instanceof ObjectNode last) {
            out.setAll(last.deepCopy());
        }
        out.put("taskId", taskId.toString());
        out.put("status", session.status());
        out.put("version", session.version());
        out.put("round", session.round());
        out.set("clarifications", readTree(session.clarificationsJson()));
        out.set("answers", answersNode(sessions.findAnswers(taskId)));
        out.set("draft", readTree(session.draftJson()));
        out.put("expiresAt", session.expiresAt().toString());
        return out;
    }

    /**
     * Перезаписывает черновик ответов целиком. Версия сессии не меняется.
     *
     * @param taskId идентификатор прогона
     * @param draft  черновики: индекс уточнения → текст
     * @throws IllegalArgumentException сессия не найдена или истекла (ответ {@code 400})
     */
    public void saveDraft(String taskId, Map<String, String> draft) {
        UUID id = parseSessionId(taskId);
        if (!sessions.updateDraft(id, toJson(draft == null ? Map.of() : draft))) {
            throw new IllegalArgumentException(SESSION_NOT_FOUND);
        }
    }

    /**
     * Пишет файлы прогона и возвращает ответ. Вызывается внутри транзакции.
     *
     * @param runId          идентификатор прогона
     * @param status         статус прогона
     * @param prefix         префикс имён файлов
     * @param markdown       исходный Markdown
     * @param cases          тест-кейсы
     * @param clarifications уточнения
     * @param filesById      план имён файлов
     * @param staleNames     имена файлов прошлого набора, подлежащие удалению
     * @param version        версия сессии для ответа
     * @return ответ API
     */
    private GenerateResponse commitRun(UUID runId, String status, String prefix, String markdown,
                                       List<TestCase> cases, List<Clarification> clarifications,
                                       Map<String, String> filesById, Collection<String> staleNames,
                                       int version) {
        List<Rendered> rendered = render(filesById, cases, clarifications);
        List<UUID> ids = artifacts.replaceRun(runId, toNew(rendered), staleNames);
        repository.replaceRun(runId, status, prefix, markdown, cases, filesById, clarifications);

        String base = props.getStorage().getBaseUrl();
        List<GeneratedFile> files = new ArrayList<>(rendered.size());
        for (int i = 0; i < rendered.size(); i++) {
            Rendered r = rendered.get(i);
            files.add(new GeneratedFile(r.id(), r.filename(), base + "/api/files/" + ids.get(i)));
        }
        return new GenerateResponse(runId.toString(), status, cases, clarifications, files, version);
    }

    /**
     * Планирует имена файлов. Имя из прошлого набора для того же id сохраняется; остальным даётся
     * свободное имя в пределах набора. Порядок ключей: тест-кейсы, {@code _clarifications} (если нужен),
     * {@code _index} (если есть тест-кейсы).
     *
     * @param cases          тест-кейсы
     * @param clarifications уточнения
     * @param prefix         префикс имён
     * @param previous       id → имя из прошлого набора сессии
     * @return id → имя файла
     */
    private Map<String, String> planNames(List<TestCase> cases, List<Clarification> clarifications,
                                          String prefix, Map<String, String> previous) {
        List<String> ids = new ArrayList<>();
        List<String> bases = new ArrayList<>();
        for (TestCase tc : cases) {
            ids.add(tc.id());
            bases.add(prefix + renderer.fileNameFor(tc));
        }
        if (hasMissingRequirements(clarifications)) {
            ids.add(KEY_CLARIFICATIONS);
            bases.add(clarificationsFileName(prefix));
        }
        if (!cases.isEmpty()) {
            ids.add(KEY_INDEX);
            bases.add(indexFileName(prefix));
        }

        String[] names = new String[ids.size()];
        Set<String> used = new HashSet<>();
        for (int i = 0; i < names.length; i++) {
            String p = previous.get(ids.get(i));
            if (p != null && used.add(p)) {
                names[i] = p;
            }
        }
        for (int i = 0; i < names.length; i++) {
            if (names[i] == null) {
                names[i] = freshName(bases.get(i), used);
            }
        }

        Map<String, String> filesById = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            filesById.put(ids.get(i), names[i]);
        }
        return filesById;
    }

    /**
     * Рендерит содержимое файлов по плану имён.
     *
     * @param filesById      id → имя файла
     * @param cases          тест-кейсы
     * @param clarifications уточнения
     * @return файлы в порядке {@code filesById}
     */
    private List<Rendered> render(Map<String, String> filesById, List<TestCase> cases,
                                  List<Clarification> clarifications) {
        Map<String, TestCase> byId = new HashMap<>();
        for (TestCase tc : cases) {
            byId.putIfAbsent(tc.id(), tc);
        }
        String clarName = filesById.get(KEY_CLARIFICATIONS);
        List<Rendered> out = new ArrayList<>(filesById.size());
        for (Map.Entry<String, String> e : filesById.entrySet()) {
            String id = e.getKey();
            String name = e.getValue();
            if (KEY_INDEX.equals(id)) {
                out.add(new Rendered(id, KIND_INDEX, name,
                        renderer.renderIndexFromSaved(cases, clarifications, filesById, clarName)));
            } else if (KEY_CLARIFICATIONS.equals(id)) {
                out.add(new Rendered(id, KIND_MARKDOWN, name, renderer.renderClarifications(clarifications)));
            } else {
                out.add(new Rendered(id, KIND_TEST_CASE, name, renderer.render(byId.get(id), clarifications)));
            }
        }
        return out;
    }

    /**
     * Переводит отрендеренные файлы в артефакты БД.
     *
     * @param rendered файлы
     * @return новые артефакты
     */
    private static List<NewArtifact> toNew(List<Rendered> rendered) {
        List<NewArtifact> out = new ArrayList<>(rendered.size());
        for (Rendered r : rendered) {
            out.add(new NewArtifact(r.kind(), r.filename(), MARKDOWN_TYPE,
                    nz(r.content()).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    /**
     * Подбирает имя, не занятое в наборе: {@code base}, затем {@code stem_2.md}, {@code stem_3.md} и т. д.
     *
     * @param base base-имя с расширением {@code .md}
     * @param used уже назначенные имена; дополняется
     * @return свободное имя
     */
    private static String freshName(String base, Set<String> used) {
        String stem = base.substring(0, base.length() - ".md".length());
        String candidate = base;
        for (int i = 2; used.contains(candidate); i++) {
            candidate = stem + "_" + i + ".md";
        }
        used.add(candidate);
        return candidate;
    }

    /**
     * Проверяет длину документа для сессии.
     *
     * @param markdown документ
     * @throws IllegalArgumentException если документ длиннее {@code app.sessions.max-markdown-chars}
     */
    private void checkSize(String markdown) {
        int maxChars = props.getSessions().getMaxMarkdownChars();
        if (markdown != null && markdown.length() > maxChars) {
            throw new IllegalArgumentException(
                    "Документ слишком большой для сессии: " + markdown.length() + " > " + maxChars);
        }
    }

    /**
     * Статус прогона по уточнениям.
     *
     * @param clarifications уточнения
     * @return {@code NEEDS_CLARIFICATION}, если есть уточнение, требующее ответа; иначе {@code DONE}
     */
    static String statusOf(List<Clarification> clarifications) {
        return hasMissingRequirements(clarifications) ? STATUS_NEEDS_CLARIFICATION : STATUS_DONE;
    }

    /**
     * Есть ли уточнения, требующие ответа. Тип {@code UNGROUNDED_ASSUMPTION} не учитывается;
     * уточнение без типа считается {@code MISSING_REQUIREMENT}.
     *
     * @param clarifications уточнения; может быть {@code null}
     * @return {@code true}, если такое уточнение найдено
     */
    static boolean hasMissingRequirements(List<Clarification> clarifications) {
        if (clarifications == null || clarifications.isEmpty()) {
            return false;
        }
        return clarifications.stream()
                .anyMatch(c -> c.type() == null || c.type() == ClarificationType.MISSING_REQUIREMENT);
    }

    /**
     * Выбирает источник Markdown: непустой {@code markdown} имеет приоритет, иначе скачивается {@code pdfToMdUrl}.
     *
     * @param request тело запроса
     * @return Markdown-текст
     * @throws IllegalArgumentException если ни одно поле не задано
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
     * Разбирает taskId сессии.
     *
     * @param taskId строка из пути
     * @return UUID
     * @throws IllegalArgumentException если значение не UUID (трактуется как «не найдена»)
     */
    private static UUID parseSessionId(String taskId) {
        try {
            return UUID.fromString(taskId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(SESSION_NOT_FOUND);
        }
    }

    /**
     * Читает состояние сессии из JSON.
     *
     * @param json JSON столбца {@code state}
     * @return состояние
     * @throws IllegalStateException если JSON не читается
     */
    private SessionState readState(String json) {
        try {
            return mapper.readValue(json, SessionState.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Ошибка чтения состояния сессии", e);
        }
    }

    /**
     * Разбирает JSON из БД в дерево.
     *
     * @param json JSON-текст
     * @return дерево JSON
     * @throws IllegalStateException если JSON не читается
     */
    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json == null ? "null" : json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Ошибка чтения JSON сессии", e);
        }
    }

    /**
     * Строит массив истории ответов для GET.
     *
     * @param rows строки истории
     * @return JSON-массив
     */
    private ArrayNode answersNode(List<AnswerRow> rows) {
        ArrayNode arr = mapper.createArrayNode();
        for (AnswerRow r : rows) {
            arr.addObject()
                    .put("round", r.round())
                    .put("testCaseId", r.testCaseId())
                    .put("question", r.question())
                    .put("topicKey", r.topicKey())
                    .put("answer", r.answer())
                    .put("answeredAt", r.answeredAt().toString());
        }
        return arr;
    }

    /**
     * Сериализует значение в JSON.
     *
     * @param value объект
     * @return JSON-строка
     * @throws IllegalStateException если сериализация не удалась
     */
    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Ошибка сериализации", e);
        }
    }

    /**
     * Строит имя файла индекса: {@code spec_} → {@code spec_index.md}, без префикса — {@code _index.md}.
     *
     * @param prefix префикс
     * @return имя файла
     */
    static String indexFileName(String prefix) {
        return prefix.endsWith("_") ? prefix + INDEX_BASENAME : prefix + "_" + INDEX_BASENAME;
    }

    /**
     * Строит имя файла уточнений по тем же правилам, что и {@link #indexFileName}.
     *
     * @param prefix префикс
     * @return имя файла
     */
    static String clarificationsFileName(String prefix) {
        return prefix.endsWith("_") ? prefix + CLARIFICATIONS_BASENAME : prefix + "_" + CLARIFICATIONS_BASENAME;
    }

    /**
     * Префикс имён файлов из имени документа: расширение отбрасывается, недопустимые символы заменяются
     * на {@code _}, длина до 40 символов, добавляется {@code _}.
     *
     * @param filename имя исходного документа, может быть {@code null}
     * @return префикс или пустая строка
     */
    static String sanitizePrefix(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        String base = filename;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        String cleaned = base.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        if (cleaned.isBlank()) {
            return "";
        }
        if (cleaned.length() > 40) {
            cleaned = cleaned.substring(0, 40);
        }
        return cleaned + "_";
    }

    /**
     * Заменяет {@code null} на пустой список.
     *
     * @param list список, может быть {@code null}
     * @param <T>  тип элементов
     * @return список или пустой список
     */
    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    /**
     * Заменяет {@code null} на пустую строку.
     *
     * @param s строка, может быть {@code null}
     * @return строка или пустая строка
     */
    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
