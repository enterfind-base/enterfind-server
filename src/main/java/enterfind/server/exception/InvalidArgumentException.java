package enterfind.server.exception;

/**
 * Exception thrown when invalid arguments are provided to a method.
 */
public class InvalidArgumentException extends RuntimeException {

    public InvalidArgumentException(String message) {
        super(message);
    }

    public InvalidArgumentException(String message, Throwable cause) {
        super(message, cause);
    }
}