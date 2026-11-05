package application.service;

import application.dto.Dtos.JournalEntry;
import application.dto.Dtos.JournalEntryRequest;
import application.repository.JournalRepository;
import application.repository.JournalRepository.JournalRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Журнал действий пользователя: валидация входа, upsert, выборка и удаление записей.
 * <p>
 * Полезная нагрузка ({@code payload}) хранится как JSON-объект и сервером не разбирается.
 */
@Service
@RequiredArgsConstructor
public class JournalService {

    /** Допустимые типы записей журнала. */
    static final Set<String> TYPES = Set.of("convert", "generate", "run");

    private final JournalRepository repository;
    private final ObjectMapper mapper;

    /**
     * Проверенная запись журнала, готовая к сохранению.
     *
     * @param type       тип записи
     * @param title      заголовок
     * @param status     статус или {@code null}
     * @param taskId     taskId или {@code null}
     * @param sourceName имя исходного документа или {@code null}
     * @param createdAt  момент создания
     * @param payload    JSON-объект полезной нагрузки
     */
    public record ValidEntry(String type, String title, String status, UUID taskId, String sourceName,
                             Instant createdAt, JsonNode payload) {
    }

    /**
     * Возвращает записи с неистёкшим сроком, новые первыми, не более 500.
     *
     * @return список записей журнала
     */
    public List<JournalEntry> list() {
        return repository.findActive().stream().map(this::toDto).toList();
    }

    /**
     * Создаёт или обновляет запись журнала с указанным id.
     *
     * @param id      идентификатор записи (UUID из пути)
     * @param request тело запроса
     * @return сохранённая запись
     * @throws IllegalArgumentException если id или тело некорректны (ответ {@code 400})
     */
    public JournalEntry upsert(String id, JournalEntryRequest request) {
        UUID uuid = parseId(id);
        ValidEntry entry = validate(request);
        String payloadJson = writeJson(entry.payload());
        repository.upsert(new JournalRow(uuid, entry.type(), entry.title(), entry.status(), entry.taskId(),
                entry.sourceName(), payloadJson, entry.createdAt()));
        return new JournalEntry(uuid.toString(), entry.type(), entry.title(), entry.status(),
                entry.taskId() == null ? null : entry.taskId().toString(), entry.sourceName(),
                entry.createdAt(), entry.payload());
    }

    /**
     * Удаляет запись по id. Отсутствие записи не считается ошибкой.
     *
     * @param id идентификатор записи
     * @throws IllegalArgumentException если id некорректен
     */
    public void delete(String id) {
        repository.delete(parseId(id));
    }

    /**
     * Очищает весь журнал.
     */
    public void deleteAll() {
        repository.deleteAll();
    }

    /**
     * Проверяет тело записи журнала.
     * <p>
     * Правила: {@code type} из {@code convert|generate|run}; {@code title} не пустой; {@code createdAt} —
     * ISO-8601 с часовым поясом; {@code taskId} — UUID или {@code null}; {@code payload} — JSON-объект
     * или {@code null} (тогда {@code {}}).
     *
     * @param request тело запроса
     * @return проверенная запись
     * @throws IllegalArgumentException при нарушении правил (ответ {@code 400})
     */
    static ValidEntry validate(JournalEntryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Пустое тело записи журнала");
        }
        if (request.type() == null || !TYPES.contains(request.type())) {
            throw new IllegalArgumentException("Некорректный тип записи журнала");
        }
        if (request.title() == null || request.title().isBlank()) {
            throw new IllegalArgumentException("Не задан заголовок записи журнала");
        }
        return new ValidEntry(request.type(), request.title(), request.status(),
                parseTaskId(request.taskId()), request.sourceName(),
                parseCreatedAt(request.createdAt()), toObjectPayload(request.payload()));
    }

    /**
     * Разбирает момент создания записи.
     *
     * @param value строка ISO-8601
     * @return момент в UTC
     * @throws IllegalArgumentException если строка не разбирается
     */
    static Instant parseCreatedAt(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Некорректная дата создания записи (createdAt)");
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Некорректная дата создания записи (createdAt)");
        }
    }

    /**
     * Разбирает необязательный taskId.
     *
     * @param value строка UUID или {@code null}/пусто
     * @return UUID или {@code null}
     * @throws IllegalArgumentException если значение не UUID
     */
    static UUID parseTaskId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Некорректный taskId");
        }
    }

    /**
     * Проверяет, что полезная нагрузка — JSON-объект; {@code null} заменяется на пустой объект.
     *
     * @param payload полезная нагрузка из запроса
     * @return JSON-объект
     * @throws IllegalArgumentException если значение не объект
     */
    static JsonNode toObjectPayload(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return JsonNodeFactory.instance.objectNode();
        }
        if (!payload.isObject()) {
            throw new IllegalArgumentException("payload должен быть JSON-объектом");
        }
        return payload;
    }

    /**
     * Разбирает id записи из пути.
     *
     * @param id строка из пути
     * @return UUID
     * @throws IllegalArgumentException если id не UUID
     */
    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Некорректный идентификатор записи журнала");
        }
    }

    /**
     * Сериализует полезную нагрузку.
     *
     * @param payload JSON-объект
     * @return JSON-строка
     * @throws IllegalStateException если сериализация не удалась
     */
    private String writeJson(JsonNode payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Ошибка сериализации payload журнала", e);
        }
    }

    /**
     * Переводит строку БД в ответ API.
     *
     * @param row строка журнала
     * @return запись для ответа
     * @throws IllegalStateException если payload в БД не читается
     */
    private JournalEntry toDto(JournalRow row) {
        try {
            return new JournalEntry(row.id().toString(), row.type(), row.title(), row.status(),
                    row.taskId() == null ? null : row.taskId().toString(), row.sourceName(),
                    row.createdAt(), mapper.readTree(row.payloadJson()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Ошибка чтения payload журнала", e);
        }
    }
}
