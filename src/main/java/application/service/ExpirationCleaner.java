package application.service;

import application.repository.ArtifactRepository;
import application.repository.ClarificationSessionRepository;
import application.repository.JobRepository;
import application.repository.JournalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Очистка записей с истёкшим сроком хранения (30 дней).
 * <p>
 * Выполняется при старте приложения и затем каждые 6 часов. Чистятся артефакты этого сервиса
 * ({@code owner_service = 'md-to-test'}), сессии и задачи генерации ({@code service = 'generate'}) и журнал.
 * Ошибка очистки логируется и не останавливает приложение.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpirationCleaner {

    /** Интервал между запусками очистки: 6 часов в миллисекундах. */
    static final long INTERVAL_MS = 6L * 60 * 60 * 1000;

    private final ArtifactRepository artifacts;
    private final ClarificationSessionRepository sessions;
    private final JournalRepository journal;
    private final JobRepository jobs;

    /**
     * Очистка при старте приложения.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOnStartup() {
        cleanup();
    }

    /**
     * Очистка по расписанию: первый запуск через 6 часов после старта, далее с тем же интервалом.
     */
    @Scheduled(fixedDelay = INTERVAL_MS, initialDelay = INTERVAL_MS)
    public void cleanupScheduled() {
        cleanup();
    }

    /**
     * Удаляет записи с истёкшим сроком; ошибки логируются.
     */
    public void cleanup() {
        try {
            log.info("Очистка по сроку: артефакты={}, сессии={}, задачи={}, журнал={}",
                    artifacts.deleteExpired(), sessions.deleteExpired(), jobs.deleteExpired(), journal.deleteExpired());
        } catch (RuntimeException e) {
            log.error("Очистка по сроку не выполнена", e);
        }
    }
}
