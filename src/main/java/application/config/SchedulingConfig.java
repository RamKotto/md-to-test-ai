package application.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Включает планировщик задач Spring: очистка записей с истёкшим сроком ({@code ExpirationCleaner}).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
