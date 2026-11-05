package application.repository;

import application.dto.Dtos.Clarification;
import application.dto.Dtos.ClarificationType;
import application.dto.Dtos.TestCase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Записывает снимок прогона генерации в схему {@code qa}: таблицы {@code generation_runs},
 * {@code test_cases} и {@code clarifications} (DDL: {@code db/init/01-schema.sql} в корне монорепозитория).
 * <p>
 * Снимок структурированного прогона (тест-кейсы и уточнения). Файлы и сессии хранятся в
 * {@code qa.artifacts} и {@code qa.clarification_sessions} (см. {@link ArtifactRepository}). Класс только
 * записывает данные (вставка, обновление и удаление строк прогона), чтения нет. Вызывается из
 * {@link application.service.GenerationFlowService} при каждом {@code generate} и ответе на уточнения,
 * внутри той же транзакции, что и запись файлов.
 * Прогон перезаписывается целиком: старые строки {@code test_cases} и {@code clarifications}
 * удаляются и вставляются заново в одной транзакции.
 */
@Repository
@RequiredArgsConstructor
public class GenerationRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /**
     * Создаёт или обновляет прогон и полностью заменяет его тест-кейсы и уточнения.
     * <p>
     * Строка {@code generation_runs} вставляется, а при конфликте по {@code id} обновляются
     * статус, префикс, исходный Markdown и {@code updated_at}. Затем удаляются и вставляются
     * строки {@code clarifications} и {@code test_cases}. Колонка {@code filename} заполняется
     * из {@code filenamesById} по id тест-кейса. Уточнение без типа сохраняется как
     * {@code MISSING_REQUIREMENT}. Текстовые значения {@code null} заменяются на пустую строку
     * там, где колонка {@code NOT NULL}.
     *
     * @param runId          идентификатор прогона (равен {@code taskId})
     * @param status         {@code DONE} или {@code NEEDS_CLARIFICATION}
     * @param filenamePrefix префикс имён файлов, может быть пустым
     * @param sourceMarkdown исходный Markdown-документ
     * @param testCases      тест-кейсы прогона; {@code null} трактуется как пустой список
     * @param filenamesById  id тест-кейса → имя файла прогона; может быть {@code null}
     *                       или не содержать id (тогда колонка {@code filename} пуста)
     * @param clarifications уточнения прогона; {@code null} трактуется как пустой список
     * @throws org.springframework.dao.DataAccessException при ошибке БД; транзакция откатывается,
     *                                                     ответ клиенту — {@code 500}
     * @throws IllegalStateException если не удалось сериализовать списки шагов или предусловий
     */
    @Transactional
    public void replaceRun(UUID runId, String status, String filenamePrefix, String sourceMarkdown,
                           List<TestCase> testCases, Map<String, String> filenamesById,
                           List<Clarification> clarifications) {
        jdbc.update("""
                INSERT INTO qa.generation_runs (id, status, filename_prefix, source_markdown, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status,
                                               filename_prefix = EXCLUDED.filename_prefix,
                                               source_markdown = EXCLUDED.source_markdown,
                                               updated_at = now()
                """,
                runId, nz(status), nz(filenamePrefix), sourceMarkdown);

        jdbc.update("DELETE FROM qa.clarifications WHERE run_id = ?", runId);
        jdbc.update("DELETE FROM qa.test_cases WHERE run_id = ?", runId);

        if (testCases != null) {
            for (TestCase tc : testCases) {
                jdbc.update("""
                        INSERT INTO qa.test_cases (run_id, id, type, title, requirement_ref, priority,
                                                   preconditions, steps, expected_result, filename)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                        """,
                        runId,
                        nz(tc.id()),
                        nz(tc.type()),
                        nz(tc.title()),
                        nz(tc.requirementRef()),
                        nz(tc.priority()),
                        toJson(tc.preconditions()),
                        toJson(tc.steps()),
                        nz(tc.expectedResult()),
                        filenamesById == null ? null : filenamesById.get(tc.id()));
            }
        }

        if (clarifications != null) {
            for (Clarification c : clarifications) {
                String type = c.type() == null
                        ? ClarificationType.MISSING_REQUIREMENT.name()
                        : c.type().name();
                jdbc.update("""
                        INSERT INTO qa.clarifications (run_id, requirement_ref, question, reason, type)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        runId, nz(c.requirementRef()), nz(c.question()), c.reason(), type);
            }
        }
    }

    /**
     * Сериализует список строк в JSON-массив для колонок {@code jsonb}.
     *
     * @param values список строк; {@code null} записывается как {@code []}
     * @return JSON-массив
     * @throws IllegalStateException если сериализация не удалась
     */
    private String toJson(List<String> values) {
        try {
            return mapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Ошибка сериализации JSON для qa.test_cases", e);
        }
    }

    /**
     * Заменяет {@code null} на пустую строку для колонок {@code NOT NULL}.
     *
     * @param s исходная строка, может быть {@code null}
     * @return {@code s} или пустая строка
     */
    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
