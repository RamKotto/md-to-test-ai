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

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<ErrorResponse> validation(Exception e) {
        String message = e instanceof MethodArgumentNotValidException manve
                ? manve.getBindingResult().getAllErrors().stream()
                .findFirst().map(err -> err.getDefaultMessage()).orElse("Некорректный запрос")
                : e.getMessage();
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(GenerationException.class)
    public ResponseEntity<ErrorResponse> generation(GenerationException e) {
        log.warn("Generation error: {}", e.getMessage());
        return build(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(PdfToMdClientException.class)
    public ResponseEntity<ErrorResponse> pdfToMd(PdfToMdClientException e) {
        log.warn("pdf-to-md error: {}", e.getMessage());
        return build(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ErrorResponse> storage(StorageException e) {
        log.error("Storage error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Ошибка хранилища: " + e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> internal(Exception e) {
        log.error("Unhandled error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка сервера");
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(Instant.now(), status.value(), message));
    }
}
