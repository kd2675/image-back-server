package image.back.server.dto;

public record ImageFileResponse(
        String fileName,
        String originalFileName,
        String imageUrl,
        String thumbnailUrl,
        boolean temporary
) {
}
