package application.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Репозиторий журнала {@code qa.journal_entries}. Срок хранения — 30 дней; в выборку попадают только
 * записи с неистёкшим сроком, не более {@link #LIMIT} штук, новые первыми.
 */
@Repository
@RequiredArgsConstructor
public class JournalRepository {

    /** Максимальное число записей, отдаваемых за один запрос. */
    public static final int LIMIT = 500;

    private final JdbcTemplate jdbc;

    /**
     * Строка журнала.
     *
     * @param id          идентификатор записи
     * @param type        {@code convert}, {@code generate} или {@code run}
     * @param title       заголовок
     * @param status      статус или {@code null}
     * @param taskId      taskId или {@code null}
     * @param sourceName  имя исходного документа или {@code null}
     * @param payloadJson JSON-объект полезной нагрузки
     * @param createdAt   момент создания записи
     */
    public record JournalRow(UUID id, String type, String title, String status, UUID taskId, String sourceName,
                             String payloadJson, Instant createdAt) {
    }

    /**
     * Возвращает записи с неистёкшим сроком, новые первыми, не более {@link #LIMIT}.
     *
     * @return список строк журнала
     */
    public List<JournalRow> findActive() {
        return jdbc.query("""
                SELECT id, type, title, status, task_id, source_name, payload::text AS payload, created_at
                FROM qa.journal_entries
                WHERE expires_at > now()
                ORDER BY created_at DESC
                LIMIT ?
                """, (rs, n) -> new JournalRow(rs.getObject("id", UUID.class), rs.getString("type"),
                rs.getString("title"), rs.getString("status"), rs.getObject("task_id", UUID.class),
                rs.getString("source_name"), rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant()), LIMIT);
    }

    /**
     * Вставляет запись или обновляет существующую с тем же id; срок хранения продлевается.
     *
     * @param row строка журнала
     */
    public void upsert(JournalRow row) {
        jdbc.update("""
                INSERT INTO qa.journal_entries (id, type, title, status, task_id, source_name, payload,
                                                created_at, updated_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, now(), now() + interval '30 days')
                ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type,
                                               title = EXCLUDED.title,
                                               status = EXCLUDED.status,
                                               task_id = EXCLUDED.task_id,
                                               source_name = EXCLUDED.source_name,
                                               payload = EXCLUDED.payload,
                                               created_at = EXCLUDED.created_at,
                                               updated_at = now(),
                                               expires_at = now() + interval '30 days'
                """,
                row.id(), row.type(), row.title(), row.status(), row.taskId(), row.sourceName(),
                row.payloadJson(), Timestamp.from(row.createdAt()));
    }

    /**
     * Удаляет запись по id.
     *
     * @param id идентификатор записи
     * @return число удалённых строк
     */
    public int delete(UUID id) {
        return jdbc.update("DELETE FROM qa.journal_entries WHERE id = ?", id);
    }

    /**
     * Очищает весь журнал.
     *
     * @return число удалённых строк
     */
    public int deleteAll() {
        return jdbc.update("DELETE FROM qa.journal_entries");
    }

    /**
     * Удаляет записи с истёкшим сроком хранения.
     *
     * @return число удалённых строк
     */
    public int deleteExpired() {
        return jdbc.update("DELETE FROM qa.journal_entries WHERE expires_at < now()");
    }
}
