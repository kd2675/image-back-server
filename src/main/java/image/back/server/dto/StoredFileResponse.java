package image.back.server.dto;

public record StoredFileResponse(
        String fileName,
        String originalFileName,
        String contentType,
        long sizeBytes,
        String downloadUrl,
        String uploadToken,
        boolean temporary,
        boolean newlyFinalized
) {
}
