package application.service;

import application.exception.ConflictException;
import application.exception.GenerationException;
import application.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Тесты жизненного цикла задач {@link JobService}: условный переход в PROCESSING, перевод в FAILED
 * при ошибке фонового выполнения, отказ пула, восстановление после перезапуска. Пул заменён очередью,
 * которую тест запускает вручную; БД заменена моком {@link JobRepository}.
 */
@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    private static final Instant START = Instant.parse("2026-11-06T10:00:00Z");

    @Mock
    JobRepository repository;

    /** Фоновые задачи, поставленные в пул; запускаются вызовом {@link #runQueued()}. */
    private final Deque<Runnable> queue = new ArrayDeque<>();

    private JobService service;

    @BeforeEach
    void setUp() {
        service = new JobService(repository, queue::add);
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
     * Повторный запуск во время выполнения: условный UPDATE не затронул строк, поэтому операция
     * отвергается с 409 и текстом контракта.
     */
    @Test
    void startAgain_whileProcessing_throwsConflict() {
        UUID taskId = UUID.randomUUID();
        when(repository.markProcessing(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startAgain(taskId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Предыдущая операция ещё выполняется. Дождитесь результата.");
    }

    /**
     * Успешный переход возвращает задачу в статусе PROCESSING с моментом запуска из БД.
     */
    @Test
    void startAgain_afterFinished_returnsProcessingJob() {
        UUID taskId = UUID.randomUUID();
        when(repository.markProcessing(taskId)).thenReturn(Optional.of(START));

        var job = service.startAgain(taskId);

        assertThat(job.status()).isEqualTo(JobRepository.PROCESSING);
        assertThat(job.error()).isNull();
        assertThat(job.startedAt()).isEqualTo(START);
    }

    /**
     * Исключение в фоне (ожидаемая доменная ошибка) переводит задачу в FAILED с его текстом.
     * HTTP-ошибки при этом не возникает: исключение не выходит за пределы пула.
     */
    @Test
    void runInBackground_domainExceptionInWork_marksJobFailedWithItsMessage() {
        UUID taskId = UUID.randomUUID();

        service.runInBackground(taskId, () -> {
            throw new GenerationException("Модель вернула невалидный JSON: нет ключа testCases");
        });
        verify(repository, never()).markFailed(eq(taskId), anyString());

        runQueued();

        verify(repository).markFailed(taskId, "Модель вернула невалидный JSON: нет ключа testCases");
    }

    /**
     * Неожиданное исключение (например, сбой БД) не раскрывается клиенту: задача получает общий текст.
     */
    @Test
    void runInBackground_unexpectedException_marksJobFailedWithGenericMessage() {
        UUID taskId = UUID.randomUUID();

        service.runInBackground(taskId, () -> {
            throw new IllegalStateException("SQL: relation qa.jobs does not exist");
        });
        runQueued();

        verify(repository).markFailed(taskId, JobService.INTERNAL);
    }

    /**
     * Успешная работа не переводит задачу в FAILED: статус DONE пишет сама работа в своей транзакции.
     */
    @Test
    void runInBackground_successfulWork_doesNotMarkFailed() {
        UUID taskId = UUID.randomUUID();
        AtomicBoolean ran = new AtomicBoolean();

        service.runInBackground(taskId, () -> ran.set(true));
        runQueued();

        assertThat(ran).isTrue();
        verify(repository, never()).markFailed(eq(taskId), anyString());
    }

    /**
     * Переполнение очереди: задача сразу переводится в FAILED с текстом про занятый сервер, исключение
     * пробрасывается (ответ 503), работа не выполняется.
     */
    @Test
    void runInBackground_queueFull_marksJobFailedAndRethrows() {
        UUID taskId = UUID.randomUUID();
        AtomicBoolean ran = new AtomicBoolean();
        JobService rejecting = new JobService(repository, task -> {
            throw new TaskRejectedException("queue is full", new RejectedExecutionException());
        });

        assertThatThrownBy(() -> rejecting.runInBackground(taskId, () -> ran.set(true)))
                .isInstanceOf(TaskRejectedException.class);

        verify(repository).markFailed(taskId, JobService.REJECTED);
        assertThat(ran).isFalse();
    }

    /**
     * При старте сервиса все задачи генерации в PROCESSING переводятся в FAILED с текстом о перезапуске.
     */
    @Test
    void afterSingletonsInstantiated_failsInterruptedJobsWithRestartMessage() {
        when(repository.failProcessing(JobService.RESTARTED)).thenReturn(2);

        service.afterSingletonsInstantiated();

        verify(repository).failProcessing("Сервис перезапущен во время обработки. Повторите операцию.");
    }
}
