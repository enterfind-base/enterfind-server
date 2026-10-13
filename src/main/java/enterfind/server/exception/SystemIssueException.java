package enterfind.server.exception;

/**
 * Exception thrown when there is a system-level issue that prevents operation completion.
 */
public class SystemIssueException extends RuntimeException {

    public SystemIssueException(String message) {
        super(message);
    }

    public SystemIssueException(String message, Throwable cause) {
        super(message, cause);
    }
}