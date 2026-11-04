package application.exception;

import application.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * Глобальный обработчик исключений REST-контроллеров. Преобразует исключения в
 * {@link ErrorResponse} с соответствующим HTTP-статусом.
 * <p>
 * Сопоставление: {@link IllegalArgumentException} и ошибки валидации — 400;
 * {@link GenerationException} и {@link PdfToMdClientException} — 502;
 * {@link StorageException} и любые прочие исключения — 500.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Ошибка некорректного запроса или доменного ограничения.
     *
     * @param e исключение; текст сообщения передаётся клиенту
     * @return ответ с кодом {@code 400 Bad Request}
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * Ошибка Bean Validation. Для {@link MethodArgumentNotValidException} в сообщение
     * попадает default message первой ошибки привязки, при её отсутствии — «Некорректный запрос».
     * Для {@link ConstraintViolationException} — текст самого исключения.
     *
     * @param e исключение валидации
     * @return ответ с кодом {@code 400 Bad Request}
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<ErrorResponse> validation(Exception e) {
        String message = e instanceof MethodArgumentNotValidException manve
                ? manve.getBindingResult().getAllErrors().stream()
                .findFirst().map(err -> err.getDefaultMessage()).orElse("Некорректный запрос")
                : e.getMessage();
        return build(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Ошибка генерации или ответа LLM (некорректный ответ модели, ошибка OpenRouter).
     * Пишет предупреждение в лог.
     *
     * @param e исключение генерации
     * @return ответ с кодом {@code 502 Bad Gateway}
     */
    @ExceptionHandler(GenerationException.class)
    public ResponseEntity<ErrorResponse> generation(GenerationException e) {
        log.warn("Generation error: {}", e.getMessage());
        return build(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    /**
     * Ошибка скачивания Markdown из pdf-to-md. Пишет предупреждение в лог.
     *
     * @param e исключение клиента pdf-to-md
     * @return ответ с кодом {@code 502 Bad Gateway}
     */
    @ExceptionHandler(PdfToMdClientException.class)
    public ResponseEntity<ErrorResponse> pdfToMd(PdfToMdClientException e) {
        log.warn("pdf-to-md error: {}", e.getMessage());
        return build(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    /**
     * Ошибка файлового хранилища. Полный стек пишется в лог с уровнем ERROR.
     *
     * @param e исключение хранилища
     * @return ответ с кодом {@code 500 Internal Server Error} и сообщением
     *         «Ошибка хранилища: …»
     */
    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ErrorResponse> storage(StorageException e) {
        log.error("Storage error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Ошибка хранилища: " + e.getMessage());
    }

    /**
     * Обработчик последней инстанции для всех прочих исключений. Полный стек пишется в лог
     * с уровнем ERROR, клиент получает обобщённое сообщение без деталей.
     *
     * @param e любое необработанное исключение
     * @return ответ с кодом {@code 500 Internal Server Error}
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> internal(Exception e) {
        log.error("Unhandled error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка сервера");
    }

    /**
     * Формирует тело ошибки с текущим временем {@link Instant#now()}.
     *
     * @param status  HTTP-статус ответа
     * @param message сообщение для клиента
     * @return {@link ResponseEntity} с {@link ErrorResponse} в теле
     */
    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(Instant.now(), status.value(), message));
    }
}
