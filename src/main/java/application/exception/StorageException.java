package application.exception;

/**
 * Ошибка файлового хранилища. Файлы теперь хранятся в БД ({@code qa.artifacts}), поэтому в текущей
 * реализации исключение не выбрасывается. Отображается в HTTP 500 через {@link GlobalExceptionHandler}.
 */
public class StorageException extends RuntimeException {

    /**
     * @param message описание ошибки
     */
    public StorageException(String message) {
        super(message);
    }

    /**
     * @param message описание ошибки
     * @param cause   исходная причина
     */
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
