package application.service;

import application.config.AppProperties;
import application.dto.Dtos.AnswerRequest;
import application.dto.Dtos.GenerateRequest;
import application.dto.Dtos.TestCase;
import application.exception.ConflictException;
import application.exception.GenerationException;
import application.repository.ArtifactRepository;
import application.repository.ClarificationSessionRepository;
import application.repository.ClarificationSessionRepository.SessionRow;
import application.repository.GenerationRepository;
import application.repository.JobRepository;
import application.repository.JobRepository.JobRow;
import application.service.SyncGenerationService.GenerationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Тесты асинхронного {@link GenerationFlowService}: синхронная часть (202, 400, 409), фоновая часть
 * (сессия для статуса DONE, перевод в FAILED при ошибке модели и при конфликте версии) и состояние
 * для GET. БД и модель заменены моками; пул задач заменён очередью, которую тест запускает вручную.
 */
@ExtendWith(MockitoExtension.class)
class GenerationFlowServiceTest {

    private static final Instant START = Instant.parse("2026-11-06T10:00:00Z");
    private static final TestCase CASE = new TestCase("TC-UI-001", "UI", "Открытие страницы входа",
            "REQ-1", List.of(), List.of("Открыть страницу входа"), "Страница входа открыта", "P1");

    @Mock
    SyncGenerationService generationService;
    @Mock
    PdfToMdClient pdfToMdClient;
    @Mock
    ArtifactRepository artifacts;
    @Mock
    ClarificationSessionRepository sessions;
    @Mock
    GenerationRepository repository;
    @Mock
    JobRepository jobRepository;

    /** Фоновые задачи; запускаются вызовом {@link #runQueued()}. */
    private final Deque<Runnable> queue = new ArrayDeque<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private AppProperties props;
    private GenerationFlowService flow;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        JobService jobs = new JobService(jobRepository, queue::add);
        flow = new GenerationFlowService(generationService, new TestCaseMarkdownRenderer(), pdfToMdClient,
                props, artifacts, sessions, repository, mapper, new TransactionTemplate(transactions), jobs);
    }

    /**
     * Выполняет все поставленные в очередь фоновые задачи в текущем потоке.
     */
    private void runQueued() {
        Runnable task;
        while ((task = queue.poll()) != null) {
            task.run();
        }
    }

    /**
     * Строка сессии с заданными статусом и версией.
     *
     * @param id      идентификатор прогона
     * @param status  статус сессии
     * @param version версия
     * @return строка сессии
     */
    private SessionRow session(UUID id, String status, int version) {
        return new SessionRow(id, status, stateJson(), "[]", "{}", version, 0,
                Instant.parse("2026-12-06T10:00:00Z"));
    }

    /**
     * JSON состояния сессии для колонки {@code state}.
     *
     * @return JSON-строка
     */
    private String stateJson() {
        try {
            return mapper.writeValueAsString(new GenerationFlowService.SessionState(
                    "# Документ", "[]", "", Map.of("TC-UI-001", "TC-UI-001.md"), mapper.createObjectNode()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Запрос ответа на уточнение.
     *
     * @param expectedVersion ожидаемая версия или {@code null}
     * @return запрос
     */
    private AnswerRequest answer(Integer expectedVersion) {
        return new AnswerRequest(List.of(new AnswerRequest.Answer("Какой срок?", "30 дней", "limits", "TC-UI-001")),
                expectedVersion);
    }

    /**
     * Возвращает UUID задачи из состояния, полученного от {@code POST}.
     *
     * @param state состояние задачи
     * @return идентификатор задачи
     */
    private static UUID taskIdOf(JsonNode state) {
        return UUID.fromString(state.get("taskId").asText());
    }

    /**
     * Первичная генерация отвечает состоянием задачи: PROCESSING, сессии нет, данных нет, работа в очереди.
     * Модель в синхронной части не вызывается.
     */
    @Test
    void generate_returnsProcessingStateAndQueuesWork() {
        when(jobRepository.insertProcessing(any())).thenReturn(START);

        ObjectNode state = flow.generate(new GenerateRequest("# Документ", null, "spec.md"));

        assertThat(state.get("jobStatus").asText()).isEqualTo("PROCESSING");
        assertThat(state.get("jobStartedAt").asText()).isEqualTo(START.toString());
        assertThat(state.get("status").isNull()).isTrue();
        assertThat(state.get("version").asInt()).isZero();
        assertThat(state.get("testCases").isEmpty()).isTrue();
        assertThat(state.get("clarifications").isEmpty()).isTrue();
        assertThat(state.get("files").isEmpty()).isTrue();
        assertThat(queue).hasSize(1);
        verify(generationService, never()).generate(anyString());
    }

    /**
     * Сессия создаётся и для статуса DONE: в фоне пишутся прогон, файлы, сессия и задача DONE.
     */
    @Test
    void generate_doneStatus_persistsSessionAndMarksJobDone() {
        when(jobRepository.insertProcessing(any())).thenReturn(START);
        when(generationService.generate(anyString()))
                .thenReturn(new GenerationResult(List.of(CASE), List.of()));
        when(artifacts.replaceRun(any(), anyList(), anyCollection())).thenAnswer(inv -> {
            List<?> files = inv.getArgument(1);
            return files.stream().map(f -> UUID.randomUUID()).toList();
        });

        UUID taskId = taskIdOf(flow.generate(new GenerateRequest("# Документ", null, "spec.md")));
        runQueued();

        verify(sessions).insert(eq(taskId), eq("DONE"), anyString(), eq("[]"));
        verify(jobRepository).markDone(taskId);
        verify(jobRepository, never()).markFailed(any(), anyString());
    }

    /**
     * Ошибка модели в фоне не даёт HTTP-ошибки: задача переходит в FAILED с текстом, сессия не создаётся.
     */
    @Test
    void generate_modelError_marksJobFailedAndCreatesNoSession() {
        when(jobRepository.insertProcessing(any())).thenReturn(START);
        when(generationService.generate(anyString()))
                .thenThrow(new GenerationException("Модель вернула невалидный JSON: нет ключа testCases"));

        UUID taskId = taskIdOf(flow.generate(new GenerateRequest("# Документ", null, null)));
        runQueued();

        verify(jobRepository).markFailed(taskId, "Модель вернула невалидный JSON: нет ключа testCases");
        verify(sessions, never()).insert(any(), anyString(), anyString(), anyString());
        verify(jobRepository, never()).markDone(any());
    }

    /**
     * Слишком большой Markdown отвергается синхронно (400), задача не создаётся.
     */
    @Test
    void generate_tooLargeMarkdown_rejectedSynchronously() {
        props.getSessions().setMaxMarkdownChars(5);

        assertThatThrownBy(() -> flow.generate(new GenerateRequest("0123456789", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("слишком большой");
        verify(jobRepository, never()).insertProcessing(any());
        assertThat(queue).isEmpty();
    }

    /**
     * Неразрешённый хост pdfToMdUrl отвергается синхронно (400), задача не создаётся.
     */
    @Test
    void generate_disallowedPdfUrl_rejectedSynchronously() {
        when(pdfToMdClient.validateUrl("http://evil.example/a.md"))
                .thenThrow(new IllegalArgumentException("Хост не разрешён: evil.example"));

        assertThatThrownBy(() -> flow.generate(new GenerateRequest(null, "http://evil.example/a.md", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Хост не разрешён: evil.example");
        verify(jobRepository, never()).insertProcessing(any());
    }

    /**
     * Ответ на уточнения при уже выполняющейся операции: условный переход не сработал, ответ 409,
     * повторная работа не ставится в очередь.
     */
    @Test
    void answer_whileJobProcessing_returnsConflict() {
        UUID taskId = UUID.randomUUID();
        when(sessions.findActive(taskId)).thenReturn(Optional.of(session(taskId, "NEEDS_CLARIFICATION", 1)));
        when(jobRepository.markProcessing(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> flow.answer(taskId.toString(), answer(1)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Предыдущая операция ещё выполняется. Дождитесь результата.");
        assertThat(queue).isEmpty();
    }

    /**
     * Несовпадение expectedVersion отвергается до перехода задачи в PROCESSING (409).
     */
    @Test
    void answer_staleExpectedVersion_returnsConflictWithoutStartingJob() {
        UUID taskId = UUID.randomUUID();
        when(sessions.findActive(taskId)).thenReturn(Optional.of(session(taskId, "NEEDS_CLARIFICATION", 2)));

        assertThatThrownBy(() -> flow.answer(taskId.toString(), answer(1)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Сессия уже изменена в другом окне. Обновите страницу.");
        verify(jobRepository, never()).markProcessing(any());
    }

    /**
     * Сессия в статусе DONE не принимает ответы (400), задача не запускается.
     */
    @Test
    void answer_doneSession_returnsBadRequest() {
        UUID taskId = UUID.randomUUID();
        when(sessions.findActive(taskId)).thenReturn(Optional.of(session(taskId, "DONE", 1)));

        assertThatThrownBy(() -> flow.answer(taskId.toString(), answer(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Сессия уже завершена, ответы не принимаются");
        verify(jobRepository, never()).markProcessing(any());
    }

    /**
     * Ответ принят: состояние с jobStatus PROCESSING и данными последней версии; работа в очереди.
     */
    @Test
    void answer_accepted_returnsProcessingStateWithCurrentVersion() {
        UUID taskId = UUID.randomUUID();
        when(sessions.findActive(taskId)).thenReturn(Optional.of(session(taskId, "NEEDS_CLARIFICATION", 3)));
        when(jobRepository.markProcessing(taskId)).thenReturn(Optional.of(START));

        ObjectNode state = flow.answer(taskId.toString(), answer(3));

        assertThat(state.get("jobStatus").asText()).isEqualTo("PROCESSING");
        assertThat(state.get("status").asText()).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(state.get("version").asInt()).isEqualTo(3);
        assertThat(queue).hasSize(1);
    }

    /**
     * Версия изменилась между синхронной проверкой и фоновой работой (под блокировкой строки): задача
     * переходит в FAILED с текстом 409, сессия не меняется.
     */
    @Test
    void answer_versionChangedInBackground_marksJobFailedWithConflictText() {
        UUID taskId = UUID.randomUUID();
        when(sessions.findActive(taskId)).thenReturn(Optional.of(session(taskId, "NEEDS_CLARIFICATION", 1)));
        when(jobRepository.markProcessing(taskId)).thenReturn(Optional.of(START));
        when(sessions.lockActive(taskId)).thenReturn(Optional.of(session(taskId, "NEEDS_CLARIFICATION", 2)));

        flow.answer(taskId.toString(), answer(1));
        runQueued();

        verify(jobRepository).markFailed(taskId, "Сессия уже изменена в другом окне. Обновите страницу.");
        verify(jobRepository, never()).markDone(any());
        verify(sessions, never()).updateTurn(any(), anyInt(), anyInt(), anyString(), anyString(), anyString());
    }

    /**
     * GET без задачи и без сессии: 400 с текстом «Сессия не найдена или истекла» (фронтенд ищет «истекл»).
     */
    @Test
    void get_unknownTask_returnsExpiredMessage() {
        UUID taskId = UUID.randomUUID();
        when(jobRepository.find(taskId)).thenReturn(Optional.empty());
        when(sessions.findActive(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> flow.get(taskId.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Сессия не найдена или истекла");
    }

    /**
     * Первая операция упала, сессии нет: GET отдаёт статус задачи и текст ошибки, данные пустые, status = null.
     */
    @Test
    void get_failedFirstOperationWithoutSession_returnsJobErrorAndEmptyData() {
        UUID taskId = UUID.randomUUID();
        when(jobRepository.find(taskId)).thenReturn(Optional.of(
                new JobRow(taskId, "FAILED", "Модель вернула невалидный JSON", START)));
        when(sessions.findActive(taskId)).thenReturn(Optional.empty());

        ObjectNode state = flow.get(taskId.toString());

        assertThat(state.get("jobStatus").asText()).isEqualTo("FAILED");
        assertThat(state.get("jobError").asText()).isEqualTo("Модель вернула невалидный JSON");
        assertThat(state.get("status").isNull()).isTrue();
        assertThat(state.get("version").asInt()).isZero();
        assertThat(state.get("testCases").isEmpty()).isTrue();
        assertThat(state.get("expiresAt").isNull()).isTrue();
    }
}
