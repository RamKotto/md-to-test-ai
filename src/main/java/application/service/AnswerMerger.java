package application.service;

import application.exception.ConflictException;
import application.repository.ClarificationSessionRepository.AnswerRow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Чистые правила работы с ответами на уточнения: дедупликация накопленной истории и проверка версии.
 * <p>
 * Строки {@code qa.clarification_answers} не меняются; схлопывание дублей выполняется только при
 * формировании контекста для модели.
 */
public final class AnswerMerger {

    /** Текст ошибки при несовпадении версии сессии. */
    static final String VERSION_CONFLICT = "Сессия уже изменена в другом окне. Обновите страницу.";

    private AnswerMerger() {
    }

    /**
     * Ответ, передаваемый модели: один на пару topicKey + question.
     *
     * @param testCaseId идентификатор тест-кейса или {@code null}
     * @param question   вопрос
     * @param answer     ответ
     * @param topicKey   ключ темы или {@code null}
     */
    public record MergedAnswer(String testCaseId, String question, String answer, String topicKey) {
    }

    /**
     * Схлопывает историю ответов: пары «topicKey + question» (без учёта краевых пробелов) встречаются
     * один раз, побеждает последний ответ. Пустые ответы пропускаются.
     * <p>
     * Порядок результата — по времени последнего ответа на пару.
     *
     * @param history история ответов сессии в порядке добавления
     * @return дедуплицированный список для модели
     */
    public static List<MergedAnswer> merge(List<AnswerRow> history) {
        Map<String, MergedAnswer> byKey = new LinkedHashMap<>();
        for (AnswerRow row : history) {
            if (row.answer() == null || row.answer().isBlank()) {
                continue;
            }
            String key = normalize(row.topicKey()) + "\u0000" + normalize(row.question());
            byKey.remove(key);
            byKey.put(key, new MergedAnswer(row.testCaseId(), row.question(), row.answer(), row.topicKey()));
        }
        return List.copyOf(byKey.values());
    }

    /**
     * Проверяет оптимистичную блокировку: если клиент передал {@code expectedVersion} и он не равен
     * текущей версии сессии, ответ отклоняется.
     *
     * @param expectedVersion версия, которую видел клиент; {@code null} — проверка не выполняется
     * @param actualVersion   текущая версия сессии
     * @throws ConflictException если версии не совпадают (ответ {@code 409})
     */
    public static void checkExpectedVersion(Integer expectedVersion, int actualVersion) {
        if (expectedVersion != null && expectedVersion != actualVersion) {
            throw new ConflictException(VERSION_CONFLICT);
        }
    }

    /**
     * Нормализует часть ключа: {@code null} → пустая строка, краевые пробелы убираются.
     *
     * @param value исходная строка
     * @return нормализованная строка
     */
    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
