package image.back.server.exception;

public class InvalidFileException extends StorageException {
    public InvalidFileException(String message) {
        super(message);
    }
}
