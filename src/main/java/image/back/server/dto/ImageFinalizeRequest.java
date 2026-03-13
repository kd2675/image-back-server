package image.back.server.dto;

public record ImageFinalizeRequest(
        String fileName,
        String targetDir
) {
}
