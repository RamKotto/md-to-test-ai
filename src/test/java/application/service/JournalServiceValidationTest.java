package application.service;

import application.dto.Dtos.JournalEntryRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Тесты валидации записей журнала {@link JournalService#validate}. БД не используется.
 */
class JournalServiceValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JournalEntryRequest request(String type, String title, String taskId, String createdAt,
                                               JsonNode payload) {
        return new JournalEntryRequest(type, title, "DONE", taskId, "spec.md", createdAt, payload);
    }

    /**
     * Корректная запись проходит, payload по умолчанию становится пустым объектом, offset переводится в UTC.
     */
    @Test
    void validate_acceptsValidEntry_andDefaultsPayload() {
        var entry = JournalService.validate(
                request("generate", "Генерация", UUID.randomUUID().toString(), "2026-11-05T10:00:00+03:00", null));

        assertThat(entry.payload().isObject()).isTrue();
        assertThat(entry.payload().size()).isZero();
        assertThat(entry.createdAt().toString()).isEqualTo("2026-11-05T07:00:00Z");
    }

    /**
     * Пустой taskId допустим и становится null.
     */
    @Test
    void validate_allowsNullTaskId() {
        var entry = JournalService.validate(
                request("convert", "PDF", null, "2026-11-05T10:00:00Z", json("{\"stats\":{}}")));

        assertThat(entry.taskId()).isNull();
        assertThat(entry.payload().has("stats")).isTrue();
    }

    /**
     * Неизвестный тип записи отклоняется.
     */
    @Test
    void validate_rejectsUnknownType() {
        assertThatThrownBy(() -> JournalService.validate(
                request("export", "X", null, "2026-11-05T10:00:00Z", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Некорректный тип записи журнала");
    }

    /**
     * Пустой заголовок отклоняется.
     */
    @Test
    void validate_rejectsBlankTitle() {
        assertThatThrownBy(() -> JournalService.validate(
                request("run", "  ", null, "2026-11-05T10:00:00Z", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Не задан заголовок записи журнала");
    }

    /**
     * Дата без часового пояса или в неверном формате отклоняется.
     */
    @Test
    void validate_rejectsInvalidCreatedAt() {
        assertThatThrownBy(() -> JournalService.validate(
                request("run", "T", null, "05.11.2026", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Некорректная дата создания записи (createdAt)");
        assertThatThrownBy(() -> JournalService.validate(
                request("run", "T", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * taskId, который не является UUID, отклоняется.
     */
    @Test
    void validate_rejectsNonUuidTaskId() {
        assertThatThrownBy(() -> JournalService.validate(
                request("generate", "T", "not-a-uuid", "2026-11-05T10:00:00Z", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Некорректный taskId");
    }

    /**
     * payload, который не является JSON-объектом (массив или строка), отклоняется.
     */
    @Test
    void validate_rejectsNonObjectPayload() {
        assertThatThrownBy(() -> JournalService.validate(
                request("run", "T", null, "2026-11-05T10:00:00Z", json("[1,2]"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("payload должен быть JSON-объектом");
        assertThatThrownBy(() -> JournalService.validate(
                request("run", "T", null, "2026-11-05T10:00:00Z", json("\"text\""))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
