package image.back.server.exception;

public class UploadRateLimitException extends RuntimeException {
    public UploadRateLimitException(String message) {
        super(message);
    }
}
