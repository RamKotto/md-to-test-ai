package application.service;

import application.exception.ConflictException;
import application.repository.ClarificationSessionRepository.AnswerRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Тесты {@link AnswerMerger}: дедупликация накопленных ответов и проверка {@code expectedVersion}.
 * БД не используется.
 */
class AnswerMergerTest {

    private static AnswerRow row(int round, String tc, String question, String topic, String answer) {
        return new AnswerRow(round, tc, question, topic, answer, Instant.parse("2026-11-05T10:00:00Z"));
    }

    /**
     * Дубль по паре topicKey + question: в модель уходит только последний ответ.
     */
    @Test
    void merge_lastAnswerWinsForSameTopicAndQuestion() {
        List<AnswerRow> history = List.of(
                row(1, "TC-1", "Какой лимит?", "limits", "10"),
                row(2, "TC-1", "Какой лимит?", "limits", "20"));

        var merged = AnswerMerger.merge(history);

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).answer()).isEqualTo("20");
    }

    /**
     * Пары с разным topicKey или разным вопросом не схлопываются.
     */
    @Test
    void merge_keepsDistinctPairs() {
        List<AnswerRow> history = List.of(
                row(1, "TC-1", "Какой лимит?", "limits", "10"),
                row(1, "TC-2", "Какой лимит?", "auth", "11"),
                row(2, "TC-3", "Какой формат?", "limits", "JSON"));

        assertThat(AnswerMerger.merge(history)).hasSize(3);
    }

    /**
     * Пустые и пробельные ответы в набор для модели не попадают; краевые пробелы в ключе игнорируются.
     */
    @Test
    void merge_skipsBlankAnswers_andTrimsKeyParts() {
        List<AnswerRow> history = List.of(
                row(1, "TC-1", "Вопрос", "topic", "   "),
                row(1, "TC-1", "Вопрос", "topic", "первый"),
                row(2, "TC-1", " Вопрос ", " topic", "второй"));

        var merged = AnswerMerger.merge(history);

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).answer()).isEqualTo("второй");
    }

    /**
     * Null-ключи нормализуются в пустые строки и не падают.
     */
    @Test
    void merge_handlesNullTopicKey() {
        List<AnswerRow> history = List.of(
                row(1, null, "Вопрос", null, "a"),
                row(2, null, "Вопрос", null, "b"));

        var merged = AnswerMerger.merge(history);

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).answer()).isEqualTo("b");
    }

    /**
     * Без expectedVersion проверка не выполняется.
     */
    @Test
    void checkExpectedVersion_passesWhenNotProvided() {
        assertThatCode(() -> AnswerMerger.checkExpectedVersion(null, 5)).doesNotThrowAnyException();
    }

    /**
     * Совпадающая версия пропускается.
     */
    @Test
    void checkExpectedVersion_passesWhenEqual() {
        assertThatCode(() -> AnswerMerger.checkExpectedVersion(3, 3)).doesNotThrowAnyException();
    }

    /**
     * Несовпадающая версия даёт ConflictException с текстом из контракта (ответ 409).
     */
    @Test
    void checkExpectedVersion_conflictWhenDifferent() {
        assertThatThrownBy(() -> AnswerMerger.checkExpectedVersion(2, 3))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Сессия уже изменена в другом окне. Обновите страницу.");
    }
}
