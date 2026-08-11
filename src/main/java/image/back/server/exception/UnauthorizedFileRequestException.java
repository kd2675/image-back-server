package image.back.server.exception;

public class UnauthorizedFileRequestException extends RuntimeException {
    public UnauthorizedFileRequestException(String message) {
        super(message);
    }
}
