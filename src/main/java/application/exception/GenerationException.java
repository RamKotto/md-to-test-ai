package application.exception;

/**
 * Ошибка генерации тест-кейсов: сбой OpenRouter, пустой или невалидный ответ модели, все
 * тест-кейсы ответа отклонены. Отображается в HTTP 502 через
 * {@link GlobalExceptionHandler}.
 */
public class GenerationException extends RuntimeException {

    /**
     * @param message описание ошибки, передаётся клиенту
     */
    public GenerationException(String message) {
        super(message);
    }

    /**
     * @param message описание ошибки, передаётся клиенту
     * @param cause   исходная причина
     */
    public GenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
