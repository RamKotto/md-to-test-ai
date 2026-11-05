package application.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий артефактов {@code qa.artifacts}: единственное место хранения файлов сервиса.
 * <p>
 * Артефакты относятся к прогону ({@code run_id = taskId} генерации). Внутри прогона файл с тем же
 * {@code filename} и {@code kind} заменяется: старая строка удаляется, новая вставляется. Все файлы
 * одного прогона пишутся в одной транзакции {@link #replaceRun}. Срок хранения — 30 дней
 * ({@code expires_at}). Владелец строк — {@link #OWNER_SERVICE}.
 */
@Repository
@RequiredArgsConstructor
public class ArtifactRepository {

    /** Значение {@code owner_service} для артефактов этого сервиса. */
    public static final String OWNER_SERVICE = "md-to-test";

    private final JdbcTemplate jdbc;

    /**
     * Новый артефакт до записи в БД.
     *
     * @param kind        тип артефакта: {@code test_case}, {@code index} или {@code markdown}
     * @param filename    имя файла внутри прогона
     * @param contentType MIME-тип для отдачи
     * @param content     байты содержимого
     */
    public record NewArtifact(String kind, String filename, String contentType, byte[] content) {
    }

    /**
     * Артефакт, прочитанный для скачивания.
     *
     * @param filename    имя файла
     * @param contentType MIME-тип
     * @param content     байты содержимого
     */
    public record StoredFile(String filename, String contentType, byte[] content) {
    }

    /**
     * Атомарно заменяет файлы прогона.
     * <p>
     * Сначала удаляются строки прогона с именами из {@code staleFilenames} (файлы прошлого набора,
     * которых нет в новом). Затем для каждого файла старая строка с тем же {@code kind} и {@code filename}
     * удаляется и вставляется новая с новым UUID. Всё выполняется в одной транзакции.
     *
     * @param runId          идентификатор прогона (taskId)
     * @param files          файлы нового набора в порядке записи
     * @param staleFilenames имена файлов прогона, которые нужно удалить; может быть {@code null}
     * @return UUID вставленных строк в том же порядке, что и {@code files}
     * @throws org.springframework.dao.DataAccessException при ошибке БД; транзакция откатывается
     */
    @Transactional
    public List<UUID> replaceRun(UUID runId, List<NewArtifact> files, Collection<String> staleFilenames) {
        if (staleFilenames != null) {
            for (String name : new LinkedHashSet<>(staleFilenames)) {
                jdbc.update("DELETE FROM qa.artifacts WHERE run_id = ? AND filename = ?", runId, name);
            }
        }
        List<UUID> ids = new ArrayList<>(files.size());
        for (NewArtifact f : files) {
            jdbc.update("DELETE FROM qa.artifacts WHERE run_id = ? AND kind = ? AND filename = ?",
                    runId, f.kind(), f.filename());
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO qa.artifacts (id, kind, owner_service, run_id, filename, content_type,
                                              content, size_bytes, sha256, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now() + interval '30 days')
                    """,
                    id, f.kind(), OWNER_SERVICE, runId, f.filename(), f.contentType(),
                    f.content(), (long) f.content().length, sha256(f.content()));
            ids.add(id);
        }
        return ids;
    }

    /**
     * Находит артефакт по UUID, если срок хранения не истёк.
     *
     * @param id идентификатор артефакта
     * @return файл или пустой результат
     */
    public Optional<StoredFile> findById(UUID id) {
        return jdbc.query(
                "SELECT filename, content_type, content FROM qa.artifacts WHERE id = ? AND expires_at > now()",
                (rs, n) -> new StoredFile(rs.getString("filename"), rs.getString("content_type"),
                        rs.getBytes("content")),
                id).stream().findFirst();
    }

    /**
     * Удаляет артефакты этого сервиса с истёкшим сроком хранения.
     *
     * @return число удалённых строк
     */
    public int deleteExpired() {
        return jdbc.update("DELETE FROM qa.artifacts WHERE owner_service = ? AND expires_at < now()",
                OWNER_SERVICE);
    }

    /**
     * Вычисляет SHA-256 содержимого в hex.
     *
     * @param content байты
     * @return hex-строка
     */
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
