package application.exception;

/**
 * Ошибка скачивания Markdown из pdf-to-md: сетевой сбой, таймаут, HTTP-ошибка или пустой документ.
 * Отображается в HTTP 502 через {@link GlobalExceptionHandler}.
 */
public class PdfToMdClientException extends RuntimeException {

    /**
     * @param message описание ошибки, передаётся клиенту
     */
    public PdfToMdClientException(String message) {
        super(message);
    }

    /**
     * @param message описание ошибки, передаётся клиенту
     * @param cause   исходная причина
     */
    public PdfToMdClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
