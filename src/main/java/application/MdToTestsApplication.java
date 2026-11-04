package application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Точка входа сервиса md-to-tests.
 * <p>
 * Включает автоконфигурацию Spring Boot и сканирование классов {@code @ConfigurationProperties}
 * (префиксы {@code app} и {@code openrouter}). После старта поднимается HTTP-сервер на порту
 * из {@code server.port} (по умолчанию 8082).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MdToTestsApplication {

    /**
     * Запускает приложение.
     *
     * @param args аргументы командной строки, передаются в {@link SpringApplication#run}
     */
    public static void main(String[] args) {
        SpringApplication.run(MdToTestsApplication.class, args);
    }
}
