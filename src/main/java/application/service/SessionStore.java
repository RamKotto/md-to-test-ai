package application.service;

import application.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory хранилище сессий уточнений: состояние прогона, нужное для ответа пользователя.
 * <p>
 * Сессии не сохраняются на диск и теряются при перезапуске. Сессия истекает через
 * {@code app.sessions.ttl-minutes}, их число ограничено {@code app.sessions.max-count}.
 * Контроллер создаёт сессию только для прогонов со статусом {@code NEEDS_CLARIFICATION}.
 */
@Service
@RequiredArgsConstructor
public class SessionStore {

    /**
     * Состояние прогона, сохранённое для ответа на уточнения.
     *
     * @param markdown       исходный Markdown-документ
     * @param testCasesJson  JSON текущих тест-кейсов, передаётся модели при ответе
     * @param filenamePrefix префикс имён файлов прогона
     * @param createdAt      момент сохранения сессии; от него отсчитывается TTL и выбирается вытесняемая сессия
     * @param filesById      id файла (id тест-кейса, {@code _index} или {@code _clarifications}) → имя файла на диске;
     *                       в конструкторе копируется в неизменяемую карту с сохранением порядка
     */
    public record Session(String markdown, String testCasesJson, String filenamePrefix, Instant createdAt,
                          Map<String, String> filesById) {
        public Session {
            filesById = filesById == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(filesById));
        }
    }

    private final AppProperties props;

    /**
     * Сессии по {@code taskId}. Карта потокобезопасна; операции над отдельными записями не атомарны
     * относительно друг друга, поэтому параллельные ответы на один {@code taskId} не сериализуются.
     */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Сохраняет сессию без префикса и без карты файлов.
     *
     * @param taskId        идентификатор прогона
     * @param markdown      исходный Markdown
     * @param testCasesJson JSON тест-кейсов
     * @see #put(String, String, String, String, Map)
     */
    public void put(String taskId, String markdown, String testCasesJson) {
        put(taskId, markdown, testCasesJson, "", Map.of());
    }

    /**
     * Сохраняет сессию с префиксом, но без карты файлов.
     *
     * @param taskId         идентификатор прогона
     * @param markdown       исходный Markdown
     * @param testCasesJson  JSON тест-кейсов
     * @param filenamePrefix префикс имён файлов
     * @see #put(String, String, String, String, Map)
     */
    public void put(String taskId, String markdown, String testCasesJson, String filenamePrefix) {
        put(taskId, markdown, testCasesJson, filenamePrefix, Map.of());
    }

    /**
     * Сохраняет сессию прогона.
     * <p>
     * Перед записью удаляются истёкшие сессии и проверяется размер Markdown через {@link #checkSize}.
     * Если карта уже содержит {@code max-count} записей, вытесняется сессия с наименьшим
     * {@code createdAt}. Это происходит даже при перезаписи существующего {@code taskId}.
     * Новая сессия получает текущее время как {@code createdAt}.
     *
     * @param taskId         идентификатор прогона
     * @param markdown       исходный Markdown; проверяется по лимиту {@code max-markdown-chars}
     * @param testCasesJson  JSON тест-кейсов
     * @param filenamePrefix префикс имён файлов; {@code null} заменяется на пустую строку
     * @param filesById      карта id → имя файла; копируется
     * @throws IllegalArgumentException если Markdown длиннее {@code app.sessions.max-markdown-chars}
     */
    public void put(String taskId, String markdown, String testCasesJson, String filenamePrefix,
                    Map<String, String> filesById) {
        cleanup();
        checkSize(markdown);
        if (sessions.size() >= props.getSessions().getMaxCount()) {
            sessions.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().createdAt()))
                    .ifPresent(e -> sessions.remove(e.getKey()));
        }
        sessions.put(taskId, new Session(markdown, testCasesJson,
                filenamePrefix == null ? "" : filenamePrefix, Instant.now(), filesById));
    }

    /**
     * Проверяет, что документ помещается в сессию по лимиту {@code app.sessions.max-markdown-chars}.
     * Длина считается в символах UTF-16 ({@link String#length()}). {@code null} проходит проверку.
     *
     * @param markdown проверяемый текст
     * @throws IllegalArgumentException если документ длиннее лимита
     */
    public void checkSize(String markdown) {
        int maxChars = props.getSessions().getMaxMarkdownChars();
        if (markdown != null && markdown.length() > maxChars) {
            throw new IllegalArgumentException(
                    "Документ слишком большой для сессии: " + markdown.length() + " > " + maxChars);
        }
    }

    /**
     * Возвращает сессию, если она есть и не истекла. Перед поиском удаляются истёкшие сессии.
     *
     * @param taskId идентификатор прогона
     * @return сессия или {@code null}, если её нет или срок жизни истёк
     */
    public Session get(String taskId) {
        cleanup();
        return sessions.get(taskId);
    }

    /**
     * Удаляет сессию. Если её нет, метод ничего не делает.
     *
     * @param taskId идентификатор прогона
     */
    public void remove(String taskId) {
        sessions.remove(taskId);
    }

    /**
     * Удаляет сессии, созданные раньше, чем {@code now - app.sessions.ttl-minutes}.
     */
    private void cleanup() {
        Instant threshold = Instant.now().minus(Duration.ofMinutes(props.getSessions().getTtlMinutes()));
        sessions.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(threshold));
    }
}
