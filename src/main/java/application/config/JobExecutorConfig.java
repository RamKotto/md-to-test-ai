package application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Пул фоновых задач асинхронных операций ({@code POST /api/generate}, ответы на уточнения).
 * <p>
 * Пул ограничен: число потоков и длина очереди задаются в {@code app.jobs}. Если очередь заполнена,
 * новая операция отклоняется с {@code 503} (см. {@link application.service.JobService#runInBackground}).
 * Незавершённые при остановке задачи не дожидаются: после перезапуска они переводятся в {@code FAILED}.
 */
@Configuration
public class JobExecutorConfig {

    /**
     * Создаёт пул фоновых задач. Единственный {@code TaskExecutor} приложения; Spring Boot не создаёт
     * свой {@code applicationTaskExecutor}, пока есть этот бин.
     *
     * @param appProps настройки приложения, включая {@code app.jobs}
     * @return пул с потоками с префиксом {@code generate-}
     */
    @Bean(name = "jobTaskExecutor")
    @Primary
    public ThreadPoolTaskExecutor jobTaskExecutor(AppProperties appProps) {
        AppProperties.Jobs jobs = appProps.getJobs();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("generate-");
        executor.setCorePoolSize(jobs.getCorePoolSize());
        executor.setMaxPoolSize(jobs.getMaxPoolSize());
        executor.setQueueCapacity(jobs.getQueueCapacity());
        return executor;
    }
}
