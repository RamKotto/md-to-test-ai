package application.service;

import application.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class SessionStore {

    public record Session(String markdown, String testCasesJson, Instant createdAt) {
    }

    private final AppProperties props;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public void put(String taskId, String markdown, String testCasesJson) {
        cleanup();
        sessions.put(taskId, new Session(markdown, testCasesJson, Instant.now()));
    }

    public Session get(String taskId) {
        cleanup();
        return sessions.get(taskId);
    }

    public void remove(String taskId) {
        sessions.remove(taskId);
    }

    private void cleanup() {
        Instant threshold = Instant.now().minus(Duration.ofMinutes(props.getSessions().getTtlMinutes()));
        sessions.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(threshold));
    }
}
