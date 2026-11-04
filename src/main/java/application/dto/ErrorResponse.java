package application.dto;

import java.time.Instant;

/**
 * Тело ответа об ошибке, формируется {@link application.exception.GlobalExceptionHandler}.
 *
 * @param timestamp момент формирования ответа; в JSON — строка ISO-8601
 * @param status    числовой HTTP-статус, совпадающий со статусом ответа
 * @param message   сообщение для клиента
 */
public record ErrorResponse(Instant timestamp, int status, String message) {
}
