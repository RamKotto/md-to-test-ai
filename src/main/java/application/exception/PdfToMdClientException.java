package application.exception;

public class PdfToMdClientException extends RuntimeException {
    public PdfToMdClientException(String message) {
        super(message);
    }

    public PdfToMdClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
