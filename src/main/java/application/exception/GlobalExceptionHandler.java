package application.exception;

import application.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import application.service.JobService;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;

/**
 * Глобальный обработчик исключений REST-контроллеров. Преобразует исключения в
 * {@link ErrorResponse} с соответствующим HTTP-статусом.
 * <p>
 * Сопоставление: {@link IllegalArgumentException} и ошибки валидации — 400;
 * ошибки разбора запроса Spring MVC ({@link MissingServletRequestParameterException},
 * {@link MissingServletRequestPartException}, {@link HttpMessageNotReadableException},
 * {@link MethodArgumentTypeMismatchException}) — 400; {@link HttpRequestMethodNotSupportedException} — 405;
 * {@link HttpMediaTypeNotSupportedException} — 415; {@link NoResourceFoundException} — 404;
 * {@link GenerationException} и {@link PdfToMdClientException} — 502;
 * {@link TaskRejectedException} (очередь фоновых задач переполнена) — 503;
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
     * Конфликт версии сессии: клиент передал {@code expectedVersion}, который не совпадает с текущей версией.
     *
     * @param e исключение конфликта; текст сообщения передаётся клиенту
     * @return ответ с кодом {@code 409 Conflict}
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(ConflictException e) {
        return build(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * Очередь фоновых задач переполнена: операция отклонена до запуска. Клиент может повторить запрос позже.
     * Пишет предупреждение в лог.
     *
     * @param e исключение пула задач
     * @return ответ с кодом {@code 503 Service Unavailable}
     */
    @ExceptionHandler(TaskRejectedException.class)
    public ResponseEntity<ErrorResponse> busy(TaskRejectedException e) {
        log.warn("Очередь фоновых задач переполнена: {}", e.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, JobService.REJECTED);
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
     * Ошибка файлового хранилища (сейчас не выбрасывается: файлы хранятся в {@code qa.artifacts}).
     * Полный стек пишется в лог с уровнем ERROR.
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
     * Метод HTTP не поддерживается для адреса.
     * <p>
     * RFC 9110 требует заголовок {@code Allow} в ответе 405. Собственный обработчик его не получает от Spring
     * автоматически, поэтому он выставляется из {@link HttpRequestMethodNotSupportedException#getSupportedHttpMethods()}.
     *
     * @param e исключение Spring MVC
     * @return ответ с кодом {@code 405 Method Not Allowed} и заголовком {@code Allow}
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> methodNotSupported(HttpRequestMethodNotSupportedException e) {
        var allowed = e.getSupportedHttpMethods();
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(h -> {
                    if (allowed != null && !allowed.isEmpty()) {
                        h.setAllow(allowed);
                    }
                })
                .body(new ErrorResponse(Instant.now(), HttpStatus.METHOD_NOT_ALLOWED.value(),
                        "Метод " + e.getMethod() + " не поддерживается для этого адреса"));
    }

    /**
     * Тип содержимого запроса (Content-Type) не поддерживается.
     *
     * @param e исключение Spring MVC
     * @return ответ с кодом {@code 415 Unsupported Media Type}
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> mediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Неподдерживаемый тип содержимого запроса (Content-Type)");
    }

    /**
     * Не передан параметр запроса, обязательный для метода контроллера.
     *
     * @param e исключение Spring MVC
     * @return ответ с кодом {@code 400 Bad Request}
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> missingParameter(MissingServletRequestParameterException e) {
        return build(HttpStatus.BAD_REQUEST, "Отсутствует параметр запроса: " + e.getParameterName());
    }

    /**
     * Не передана часть multipart-запроса, обязательная для метода контроллера.
     *
     * @param e исключение Spring MVC
     * @return ответ с кодом {@code 400 Bad Request}
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> missingPart(MissingServletRequestPartException e) {
        return build(HttpStatus.BAD_REQUEST, "Отсутствует часть запроса: " + e.getRequestPartName());
    }

    /**
     * Тело запроса не удалось прочитать или оно не соответствует ожидаемой схеме JSON.
     * Детали парсера в ответ не передаются.
     *
     * @param e исключение чтения тела
     * @return ответ с кодом {@code 400 Bad Request}
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> notReadable(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "Некорректное тело запроса");
    }

    /**
     * Значение параметра пути или запроса не приводится к нужному типу.
     *
     * @param e исключение преобразования типа
     * @return ответ с кодом {@code 400 Bad Request}
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e) {
        return build(HttpStatus.BAD_REQUEST, "Некорректное значение параметра: " + e.getName());
    }

    /**
     * Ресурс не найден (статический или неизвестный путь, см. {@link NoResourceFoundException}).
     *
     * @param e исключение Spring MVC
     * @return ответ с кодом {@code 404 Not Found}
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NoResourceFoundException e) {
        return build(HttpStatus.NOT_FOUND, "Ресурс не найден");
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
