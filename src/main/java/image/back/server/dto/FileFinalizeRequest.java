package image.back.server.dto;

public record FileFinalizeRequest(
        String fileName,
        String targetDir,
        String uploadToken
) {
}
