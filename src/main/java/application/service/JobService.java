package application.service;

import application.exception.ConflictException;
import application.exception.GenerationException;
import application.exception.PdfToMdClientException;
import application.repository.JobRepository;
import application.repository.JobRepository.JobRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Жизненный цикл асинхронных операций генерации ({@code qa.jobs}, {@code service = 'generate'}).
 * <p>
 * Метод {@link #runInBackground} ставит работу в ограниченный пул ({@code jobTaskExecutor}). Исключение
 * фоновой работы не уходит в HTTP: задача переводится в {@link JobRepository#FAILED} с понятным текстом
 * ({@link #userMessage}). Неожиданные ошибки логируются с трассировкой, клиент получает общий текст.
 * <p>
 * При старте сервиса задачи, оставшиеся в {@code PROCESSING}, переводятся в {@code FAILED}
 * (см. {@link #afterSingletonsInstantiated}). Сервис рассчитан на один экземпляр с одной БД.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobService implements SmartInitializingSingleton {

    /** Текст конфликта, если по задаче уже идёт операция (ответ {@code 409}). */
    static final String BUSY = "Предыдущая операция ещё выполняется. Дождитесь результата.";

    /** Текст ошибки для задач, которые были в обработке во время перезапуска сервиса. */
    static final String RESTARTED = "Сервис перезапущен во время обработки. Повторите операцию.";

    /** Текст ошибки, если очередь фоновых задач переполнена (ответ {@code 503}). */
    public static final String REJECTED = "Сервер занят обработкой других операций. Повторите позже.";

    /** Текст для неожиданных ошибок: детали (SQL, стек) клиенту не передаются. */
    static final String INTERNAL = "Внутренняя ошибка при обработке. Повторите операцию.";

    private final JobRepository repository;
    private final TaskExecutor executor;

    /**
     * Создаёт задачу в статусе {@code PROCESSING}.
     *
     * @param taskId идентификатор задачи
     * @return строка задачи в статусе {@code PROCESSING}
     */
    public JobRow create(UUID taskId) {
        return new JobRow(taskId, JobRepository.PROCESSING, null, repository.insertProcessing(taskId));
    }

    /**
     * Переводит существующую задачу в {@code PROCESSING} условным UPDATE ({@code WHERE status <> 'PROCESSING'}).
     *
     * @param taskId идентификатор задачи
     * @return строка задачи в статусе {@code PROCESSING}
     * @throws ConflictException если задача уже выполняется или не найдена (ответ {@code 409})
     */
    public JobRow startAgain(UUID taskId) {
        Instant startedAt = repository.markProcessing(taskId)
                .orElseThrow(() -> new ConflictException(BUSY));
        return new JobRow(taskId, JobRepository.PROCESSING, null, startedAt);
    }

    /**
     * Переводит задачу в {@code DONE}. Вызывать внутри транзакции той же работы, чтобы результат и статус
     * фиксировались атомарно.
     *
     * @param taskId идентификатор задачи
     */
    public void markDone(UUID taskId) {
        repository.markDone(taskId);
    }

    /**
     * Переводит задачу в {@code FAILED} с текстом ошибки.
     *
     * @param taskId идентификатор задачи
     * @param error  текст для клиента
     */
    public void markFailed(UUID taskId, String error) {
        repository.markFailed(taskId, error);
    }

    /**
     * Находит задачу, если срок хранения не истёк.
     *
     * @param taskId идентификатор задачи
     * @return строка задачи или пустой результат
     */
    public Optional<JobRow> find(UUID taskId) {
        return repository.find(taskId);
    }

    /**
     * Ставит работу в фоновый пул. Ошибки работы перехватываются и записываются в задачу как {@code FAILED}.
     * <p>
     * Если очередь пула переполнена, задача сразу переводится в {@code FAILED} с текстом {@link #REJECTED},
     * а исключение {@link TaskRejectedException} пробрасывается (ответ {@code 503}).
     *
     * @param taskId идентификатор задачи
     * @param work   работа; должна сама писать результат (включая {@code DONE} внутри транзакции)
     * @throws TaskRejectedException если пул отклонил задачу
     */
    public void runInBackground(UUID taskId, Runnable work) {
        try {
            executor.execute(() -> runSafely(taskId, work));
        } catch (TaskRejectedException e) {
            log.warn("Очередь фоновых задач переполнена, задача {} отклонена", taskId);
            markFailed(taskId, REJECTED);
            throw e;
        }
    }

    /**
     * Удаляет задачи генерации с истёкшим сроком хранения.
     *
     * @return число удалённых строк
     */
    public int deleteExpired() {
        return repository.deleteExpired();
    }

    /**
     * Восстановление после перезапуска: задачи в {@code PROCESSING} переводятся в {@code FAILED}.
     * <p>
     * Выполняется в {@link SmartInitializingSingleton}, а не по {@code ApplicationReadyEvent}: этот шаг идёт
     * до старта HTTP-сервера, поэтому новая задача не может быть ошибочно помечена как прерванная.
     * Ошибка БД логируется и не останавливает запуск (как и очистка по сроку).
     */
    @Override
    public void afterSingletonsInstantiated() {
        try {
            int restarted = repository.failProcessing(RESTARTED);
            if (restarted > 0) {
                log.warn("После перезапуска переведено в FAILED прерванных задач: {}", restarted);
            }
        } catch (RuntimeException e) {
            log.error("Не удалось перевести прерванные задачи в FAILED", e);
        }
    }

    /**
     * Выполняет работу и фиксирует её неуспешное завершение.
     *
     * @param taskId идентификатор задачи
     * @param work   работа
     */
    private void runSafely(UUID taskId, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            String message = userMessage(e);
            if (message == null) {
                log.error("Задача {}: неожиданная ошибка в фоне", taskId, e);
                message = INTERNAL;
            } else {
                log.warn("Задача {} завершилась ошибкой: {}", taskId, message);
            }
            try {
                repository.markFailed(taskId, message);
            } catch (RuntimeException ex) {
                log.error("Не удалось записать FAILED для задачи {}", taskId, ex);
            }
        }
    }

    /**
     * Текст ошибки для клиента. Ожидаемые доменные ошибки отдают своё сообщение; прочие — {@code null}.
     *
     * @param e исключение фоновой работы
     * @return сообщение или {@code null}, если сообщение не предназначено для клиента
     */
    static String userMessage(RuntimeException e) {
        boolean expected = e instanceof IllegalArgumentException
                || e instanceof ConflictException
                || e instanceof GenerationException
                || e instanceof PdfToMdClientException;
        if (!expected || e.getMessage() == null || e.getMessage().isBlank()) {
            return null;
        }
        return e.getMessage();
    }
}
