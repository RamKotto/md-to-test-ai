package application.exception;

/**
 * Ошибка файлового хранилища: не удалось создать каталог, записать или откатить файлы.
 * Отображается в HTTP 500 через {@link GlobalExceptionHandler}.
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
