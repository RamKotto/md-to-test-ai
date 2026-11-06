package application.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий состояния асинхронных операций {@code qa.jobs} для {@code service = 'generate'}
 * (первичная генерация и ответы на уточнения).
 * <p>
 * Статусы: {@link #PROCESSING}, {@link #DONE}, {@link #FAILED}. Переход в {@code PROCESSING} выполняется
 * условным UPDATE ({@link #markProcessing}), поэтому две параллельные операции по одной задаче не запускаются.
 * Срок хранения — 30 дней, продлевается при каждом переходе состояния.
 */
@Repository
@RequiredArgsConstructor
public class JobRepository {

    /** Значение столбца {@code service} для операций генерации. */
    public static final String SERVICE = "generate";

    /** Операция выполняется. */
    public static final String PROCESSING = "PROCESSING";

    /** Операция завершена успешно. */
    public static final String DONE = "DONE";

    /** Операция завершилась ошибкой; текст в {@code error}. */
    public static final String FAILED = "FAILED";

    private final JdbcTemplate jdbc;

    /**
     * Строка задачи.
     *
     * @param taskId    идентификатор задачи
     * @param status    {@code PROCESSING}, {@code DONE} или {@code FAILED}
     * @param error     текст ошибки для {@code FAILED}, иначе {@code null}
     * @param startedAt момент запуска текущей операции
     */
    public record JobRow(UUID taskId, String status, String error, Instant startedAt) {
    }

    /**
     * Создаёт задачу в статусе {@link #PROCESSING}.
     *
     * @param taskId идентификатор задачи
     * @return момент запуска ({@code started_at}), посчитанный БД
     * @throws org.springframework.dao.DuplicateKeyException если задача с таким id уже есть
     */
    public Instant insertProcessing(UUID taskId) {
        return jdbc.queryForObject("""
                INSERT INTO qa.jobs (task_id, service, status, started_at, updated_at, expires_at)
                VALUES (?, ?, 'PROCESSING', now(), now(), now() + interval '30 days')
                RETURNING started_at
                """, (rs, n) -> rs.getTimestamp("started_at").toInstant(), taskId, SERVICE);
    }

    /**
     * Условный переход в {@link #PROCESSING}: UPDATE выполняется только если задача не в статусе
     * {@code PROCESSING}. Проверка и установка статуса выполняются одной строкой SQL.
     *
     * @param taskId идентификатор задачи
     * @return момент запуска операции, если переход выполнен; пусто, если задача уже выполняется
     *         или не найдена
     */
    public Optional<Instant> markProcessing(UUID taskId) {
        return jdbc.query("""
                UPDATE qa.jobs
                SET status = 'PROCESSING', error = NULL, started_at = now(), finished_at = NULL,
                    updated_at = now(), expires_at = now() + interval '30 days'
                WHERE task_id = ? AND service = ? AND status <> 'PROCESSING'
                RETURNING started_at
                """, (rs, n) -> rs.getTimestamp("started_at").toInstant(), taskId, SERVICE)
                .stream().findFirst();
    }

    /**
     * Переводит задачу в {@link #DONE}; текст ошибки очищается.
     *
     * @param taskId идентификатор задачи
     */
    public void markDone(UUID taskId) {
        jdbc.update("""
                UPDATE qa.jobs
                SET status = 'DONE', error = NULL, finished_at = now(), updated_at = now(),
                    expires_at = now() + interval '30 days'
                WHERE task_id = ? AND service = ?
                """, taskId, SERVICE);
    }

    /**
     * Переводит задачу в {@link #FAILED} с понятным текстом ошибки.
     *
     * @param taskId идентификатор задачи
     * @param error  текст для клиента
     */
    public void markFailed(UUID taskId, String error) {
        jdbc.update("""
                UPDATE qa.jobs
                SET status = 'FAILED', error = ?, finished_at = now(), updated_at = now(),
                    expires_at = now() + interval '30 days'
                WHERE task_id = ? AND service = ?
                """, error, taskId, SERVICE);
    }

    /**
     * Находит задачу, если срок хранения не истёк.
     *
     * @param taskId идентификатор задачи
     * @return строка задачи или пустой результат
     */
    public Optional<JobRow> find(UUID taskId) {
        return jdbc.query("""
                SELECT task_id, status, error, started_at
                FROM qa.jobs
                WHERE task_id = ? AND service = ? AND expires_at > now()
                """, (rs, n) -> new JobRow(rs.getObject("task_id", UUID.class), rs.getString("status"),
                rs.getString("error"), rs.getTimestamp("started_at").toInstant()), taskId, SERVICE)
                .stream().findFirst();
    }

    /**
     * Переводит все задачи генерации в {@link #PROCESSING} в {@link #FAILED} с заданным текстом.
     * Используется при старте сервиса: фоновые потоки прошлого запуска уже не работают.
     *
     * @param error текст ошибки
     * @return число обновлённых задач
     */
    public int failProcessing(String error) {
        return jdbc.update("""
                UPDATE qa.jobs
                SET status = 'FAILED', error = ?, finished_at = now(), updated_at = now(),
                    expires_at = now() + interval '30 days'
                WHERE service = ? AND status = 'PROCESSING'
                """, error, SERVICE);
    }

    /**
     * Удаляет задачи генерации с истёкшим сроком хранения.
     *
     * @return число удалённых строк
     */
    public int deleteExpired() {
        return jdbc.update("DELETE FROM qa.jobs WHERE service = ? AND expires_at < now()", SERVICE);
    }
}
