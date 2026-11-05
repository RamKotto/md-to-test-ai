package application.exception;

/**
 * Конфликт версий: сессия уже изменена в другом окне. Отображается в ответ {@code 409}.
 */
public class ConflictException extends RuntimeException {

    /**
     * @param message сообщение для клиента
     */
    public ConflictException(String message) {
        super(message);
    }
}
