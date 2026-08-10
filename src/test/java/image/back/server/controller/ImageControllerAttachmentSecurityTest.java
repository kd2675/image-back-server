package image.back.server.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import image.back.server.dto.FileFinalizeRequest;
import image.back.server.exception.InvalidFileException;
import image.back.server.service.ImageService;
import org.junit.jupiter.api.Test;

class ImageControllerAttachmentSecurityTest {
    private final ImageService imageService = mock(ImageService.class);
    private final ImageController imageController = new ImageController(imageService, "expected-token");

    @Test
    void finalizeAttachment_invalidInternalToken_rejectsBeforeStorageAccess() {
        assertThatThrownBy(() -> imageController.finalizeAttachment(
                new FileFinalizeRequest("temp/files/2026/08/10/file.pdf", "semo/attachments/feedback/31"),
                "wrong-token"
        )).isInstanceOf(InvalidFileException.class);

        verifyNoInteractions(imageService);
    }

    @Test
    void constructor_blankInternalToken_rejectsStartup() {
        assertThatThrownBy(() -> new ImageController(imageService, " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("attachment.internal-token");
    }
}
