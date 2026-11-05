package application.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий сессий уточнений {@code qa.clarification_sessions} (service = {@code generate}) и
 * истории ответов {@code qa.clarification_answers}.
 * <p>
 * Ответы только добавляются ({@link #insertAnswer}); строки не изменяются и не удаляются до истечения
 * срока сессии. Срок хранения — 30 дней, обновляется при каждом изменении сессии.
 * Метод {@link #lockActive} должен вызываться внутри транзакции.
 */
@Repository
@RequiredArgsConstructor
public class ClarificationSessionRepository {

    /** Значение столбца {@code service} для сессий генерации. */
    public static final String SERVICE = "generate";

    private final JdbcTemplate jdbc;

    /**
     * Строка сессии.
     *
     * @param taskId             идентификатор прогона
     * @param status             {@code NEEDS_CLARIFICATION} или {@code DONE}
     * @param stateJson          JSON столбца {@code state}
     * @param clarificationsJson JSON столбца {@code clarifications}
     * @param draftJson          JSON столбца {@code draft}
     * @param version            версия сессии (растёт при каждом ответе на уточнения)
     * @param round              номер последнего раунда ответов
     * @param expiresAt          срок хранения сессии
     */
    public record SessionRow(UUID taskId, String status, String stateJson, String clarificationsJson,
                             String draftJson, int version, int round, Instant expiresAt) {
    }

    /**
     * Строка истории ответа.
     *
     * @param round      раунд, в котором дан ответ
     * @param testCaseId идентификатор тест-кейса или {@code null}
     * @param question   вопрос
     * @param topicKey   ключ темы или {@code null}
     * @param answer     текст ответа (непустой)
     * @param answeredAt момент ответа
     */
    public record AnswerRow(int round, String testCaseId, String question, String topicKey, String answer,
                            Instant answeredAt) {
    }

    private static final String SESSION_COLUMNS = """
            task_id, status, state::text AS state, clarifications::text AS clarifications,
            draft::text AS draft, version, round, expires_at
            """;

    /**
     * Создаёт сессию с версией 1 и раундом 0.
     *
     * @param taskId             идентификатор прогона
     * @param status             статус прогона
     * @param stateJson          JSON состояния сессии
     * @param clarificationsJson JSON уточнений
     */
    public void insert(UUID taskId, String status, String stateJson, String clarificationsJson) {
        jdbc.update("""
                INSERT INTO qa.clarification_sessions (task_id, service, status, state, clarifications, draft,
                                                       version, round, created_at, updated_at, expires_at)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, '{}'::jsonb, 1, 0, now(), now(), now() + interval '30 days')
                """, taskId, SERVICE, status, stateJson, clarificationsJson);
    }

    /**
     * Находит неистёкшую сессию без блокировки.
     *
     * @param taskId идентификатор прогона
     * @return строка сессии или пустой результат
     */
    public Optional<SessionRow> findActive(UUID taskId) {
        return jdbc.query("SELECT " + SESSION_COLUMNS + """
                FROM qa.clarification_sessions
                WHERE task_id = ? AND service = ? AND expires_at > now()
                """, (rs, n) -> mapSession(rs), taskId, SERVICE).stream().findFirst();
    }

    /**
     * Находит неистёкшую сессию и блокирует строку до конца транзакции ({@code SELECT ... FOR UPDATE}).
     *
     * @param taskId идентификатор прогона
     * @return строка сессии или пустой результат
     */
    public Optional<SessionRow> lockActive(UUID taskId) {
        return jdbc.query("SELECT " + SESSION_COLUMNS + """
                FROM qa.clarification_sessions
                WHERE task_id = ? AND service = ? AND expires_at > now()
                FOR UPDATE
                """, (rs, n) -> mapSession(rs), taskId, SERVICE).stream().findFirst();
    }

    /**
     * Перезаписывает черновик целиком; версия не меняется.
     *
     * @param taskId    идентификатор прогона
     * @param draftJson JSON черновика
     * @return {@code true}, если сессия найдена и не истекла
     */
    public boolean updateDraft(UUID taskId, String draftJson) {
        return jdbc.update("""
                UPDATE qa.clarification_sessions
                SET draft = ?::jsonb, updated_at = now(), expires_at = now() + interval '30 days'
                WHERE task_id = ? AND service = ? AND expires_at > now()
                """, draftJson, taskId, SERVICE) > 0;
    }

    /**
     * Сохраняет результат ответа на уточнения.
     *
     * @param taskId             идентификатор прогона
     * @param version            новая версия
     * @param round              новый раунд
     * @param status             статус прогона
     * @param stateJson          JSON состояния
     * @param clarificationsJson JSON уточнений
     */
    public void updateTurn(UUID taskId, int version, int round, String status, String stateJson,
                           String clarificationsJson) {
        jdbc.update("""
                UPDATE qa.clarification_sessions
                SET version = ?, round = ?, status = ?, state = ?::jsonb, clarifications = ?::jsonb,
                    updated_at = now(), expires_at = now() + interval '30 days'
                WHERE task_id = ? AND service = ?
                """, version, round, status, stateJson, clarificationsJson, taskId, SERVICE);
    }

    /**
     * Добавляет строку истории ответов.
     *
     * @param taskId     идентификатор прогона
     * @param round      раунд ответа
     * @param testCaseId идентификатор тест-кейса или {@code null}
     * @param question   вопрос
     * @param topicKey   ключ темы или {@code null}
     * @param answer     текст ответа
     */
    public void insertAnswer(UUID taskId, int round, String testCaseId, String question, String topicKey,
                             String answer) {
        jdbc.update("""
                INSERT INTO qa.clarification_answers (task_id, round, test_case_id, question, topic_key, answer)
                VALUES (?, ?, ?, ?, ?, ?)
                """, taskId, round, testCaseId, question, topicKey, answer);
    }

    /**
     * Возвращает всю историю ответов сессии в порядке добавления.
     *
     * @param taskId идентификатор прогона
     * @return список строк истории
     */
    public List<AnswerRow> findAnswers(UUID taskId) {
        return jdbc.query("""
                SELECT round, test_case_id, question, topic_key, answer, answered_at
                FROM qa.clarification_answers
                WHERE task_id = ?
                ORDER BY id
                """, (rs, n) -> new AnswerRow(rs.getInt("round"), rs.getString("test_case_id"),
                rs.getString("question"), rs.getString("topic_key"), rs.getString("answer"),
                rs.getTimestamp("answered_at").toInstant()), taskId);
    }

    /**
     * Удаляет сессии генерации с истёкшим сроком (история ответов удаляется каскадно).
     *
     * @return число удалённых сессий
     */
    public int deleteExpired() {
        return jdbc.update("DELETE FROM qa.clarification_sessions WHERE service = ? AND expires_at < now()",
                SERVICE);
    }

    /**
     * Маппит строку сессии.
     *
     * @param rs строка результата
     * @return строка сессии
     * @throws SQLException при ошибке чтения
     */
    private static SessionRow mapSession(ResultSet rs) throws SQLException {
        Timestamp expires = rs.getTimestamp("expires_at");
        return new SessionRow(rs.getObject("task_id", UUID.class), rs.getString("status"),
                rs.getString("state"), rs.getString("clarifications"), rs.getString("draft"),
                rs.getInt("version"), rs.getInt("round"), expires.toInstant());
    }
}
