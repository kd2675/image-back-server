package image.back.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import image.back.server.exception.InvalidFileException;
import image.back.server.exception.ImageNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

class ImageServiceImplAttachmentTest {
    @TempDir
    private Path uploadDirectory;

    private ImageServiceImpl imageService;

    @BeforeEach
    void setUp() {
        imageService = new ImageServiceImpl();
        ReflectionTestUtils.setField(imageService, "uploadDir", uploadDirectory.toString());
        ReflectionTestUtils.setField(imageService, "tempRetentionMinutes", 180L);
        ReflectionTestUtils.setField(imageService, "attachmentMaxSizeBytes", 1024L * 1024L);
        ReflectionTestUtils.setField(imageService, "attachmentOrphanGraceMinutes", 0L);
        imageService.initializeUploadRoot();
    }

    @Test
    void finalizeTempFile_confirmedRetry_returnsSameFinalizedFileWithoutOwnership() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "운영계획.pdf",
                "application/pdf",
                "%PDF-1.7\nattachment".getBytes(StandardCharsets.UTF_8)
        );

        var temporary = imageService.storeTempFile(file, "http://localhost:8081");
        var first = imageService.finalizeTempFile(temporary.fileName(), "semo/attachments/decision/1", "http://localhost:8081");
        imageService.confirmFinalizedAttachment(first.fileName());
        var second = imageService.finalizeTempFile(temporary.fileName(), "semo/attachments/decision/1", "http://localhost:8081");

        assertThat(second)
                .returns(first.fileName(), item -> item.fileName())
                .returns(first.sizeBytes(), item -> item.sizeBytes())
                .returns(false, item -> item.temporary())
                .returns(false, item -> item.newlyFinalized())
                .returns(true, item -> imageService.loadFile(item.fileName()).exists());
    }

    @Test
    void finalizeTempFile_pendingRetry_rejectsConcurrentOwnership() {
        var temporary = imageService.storeTempFile(pdfFile(), "http://localhost:8081");
        imageService.finalizeTempFile(
                temporary.fileName(),
                "semo/attachments/decision/1",
                "http://localhost:8081"
        );

        assertThatThrownBy(() -> imageService.finalizeTempFile(
                temporary.fileName(),
                "semo/attachments/decision/1",
                "http://localhost:8081"
        )).isInstanceOf(image.back.server.exception.StorageException.class)
                .hasMessageContaining("already in progress");
    }

    @Test
    void finalizeTempFile_unconfirmedFile_isPendingUntilConfirmed() {
        var temporary = imageService.storeTempFile(pdfFile(), "http://localhost:8081");
        var finalized = imageService.finalizeTempFile(
                temporary.fileName(),
                "semo/attachments/decision/1",
                "http://localhost:8081"
        );

        assertThat(imageService.getPendingFinalizedAttachments())
                .singleElement()
                .returns(finalized.fileName(), item -> item.fileName());
        assertThat(finalized.newlyFinalized()).isTrue();

        imageService.confirmFinalizedAttachment(finalized.fileName());

        assertThat(imageService.getPendingFinalizedAttachments()).isEmpty();
        assertThat(imageService.loadFile(finalized.fileName()).exists()).isTrue();
    }

    @Test
    void loadPublicFile_finalizedSemoAttachment_throwsNotFound() {
        var temporary = imageService.storeTempFile(pdfFile(), "http://localhost:8081");
        var finalized = imageService.finalizeTempFile(
                temporary.fileName(),
                "semo/attachments/feedback/31",
                "http://localhost:8081"
        );

        assertThatThrownBy(() -> imageService.loadPublicFile(finalized.fileName()))
                .isInstanceOf(ImageNotFoundException.class);
    }

    @Test
    void deleteFinalizedAttachment_pendingOrphan_removesFileAndClaim() {
        var temporary = imageService.storeTempFile(pdfFile(), "http://localhost:8081");
        var finalized = imageService.finalizeTempFile(
                temporary.fileName(),
                "semo/attachments/feedback/31",
                "http://localhost:8081"
        );

        assertThat(imageService.deleteFinalizedAttachment(finalized.fileName())).isTrue();

        assertThat(imageService.getPendingFinalizedAttachments()).isEmpty();
        assertThatThrownBy(() -> imageService.loadFile(finalized.fileName()))
                .isInstanceOf(ImageNotFoundException.class);
    }

    @Test
    void storeTempFile_extensionAndSignatureMismatch_throwsInvalidFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "위장파일.pdf",
                "application/pdf",
                "not-a-pdf".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> imageService.storeTempFile(file, "http://localhost:8081"))
                .isInstanceOf(InvalidFileException.class)
                .hasMessage("Attachment file signature is invalid.");
    }

    @Test
    void storeTempFile_executableExtension_throwsInvalidFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "malware.exe",
                "application/octet-stream",
                new byte[] {0x4d, 0x5a, 0x00, 0x00}
        );

        assertThatThrownBy(() -> imageService.storeTempFile(file, "http://localhost:8081"))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("Unsupported attachment file extension");
    }

    @Test
    void finalizeTempImage_attachmentPath_throwsInvalidFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "운영계획.pdf",
                "application/pdf",
                "%PDF-1.7\nattachment".getBytes(StandardCharsets.UTF_8)
        );
        var temporary = imageService.storeTempFile(file, "http://localhost:8081");

        assertThatThrownBy(() -> imageService.finalizeTempImage(
                temporary.fileName(),
                "semo/images",
                "http://localhost:8081"
        )).isInstanceOf(InvalidFileException.class);
    }

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile(
                "file",
                "운영계획.pdf",
                "application/pdf",
                "%PDF-1.7\nattachment".getBytes(StandardCharsets.UTF_8)
        );
    }
}
