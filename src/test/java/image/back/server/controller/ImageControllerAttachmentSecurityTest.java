package image.back.server.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import image.back.server.dto.FileFinalizeRequest;
import image.back.server.exception.UnauthorizedFileRequestException;
import image.back.server.service.ImageService;
import image.back.server.service.AttachmentUploadRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;

class ImageControllerAttachmentSecurityTest {
    @TempDir
    private Path temporaryDirectory;

    private final ImageService imageService = mock(ImageService.class);
    private final AttachmentUploadRateLimiter rateLimiter = mock(AttachmentUploadRateLimiter.class);
    private final ImageController imageController = new ImageController(imageService, rateLimiter, "expected-token");

    @Test
    void finalizeAttachment_invalidInternalToken_rejectsBeforeStorageAccess() {
        assertThatThrownBy(() -> imageController.finalizeAttachment(
                new FileFinalizeRequest(
                        "temp/files/2026/08/10/file.pdf", "semo/attachments/feedback/31", "upload-token"
                ),
                "wrong-token"
        )).isInstanceOf(UnauthorizedFileRequestException.class);

        verifyNoInteractions(imageService);
    }

    @Test
    void constructor_blankInternalToken_rejectsStartup() {
        assertThatThrownBy(() -> new ImageController(imageService, rateLimiter, " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("attachment.internal-token");
    }

    @Test
    void getImage_pngResource_returnsImageContentType() throws Exception {
        Path imagePath = temporaryDirectory.resolve("club-logo.png");
        Files.write(imagePath, new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        when(imageService.loadImage("semo/clubs/club-logo.png", null, null))
                .thenReturn(new FileSystemResource(imagePath));

        var response = imageController.getImage("semo/clubs/club-logo.png", null, null);

        org.assertj.core.api.Assertions.assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.IMAGE_PNG);
    }
}
