package image.back.server.service;

import image.back.server.dto.ImageFileResponse;
import image.back.server.dto.PendingFinalizedFileResponse;
import image.back.server.dto.StoredFileResponse;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface ImageService {
    String storeImage(MultipartFile file);
    ImageFileResponse storeTempImage(MultipartFile file, String baseUrl);
    ImageFileResponse finalizeTempImage(String fileName, String targetDir, String baseUrl);
    Resource loadImage(String fileName, Integer width, Integer height);
    StoredFileResponse storeTempFile(MultipartFile file, String baseUrl);
    StoredFileResponse finalizeTempFile(String fileName, String targetDir, String uploadToken, String baseUrl);
    boolean confirmFinalizedAttachment(String fileName);
    boolean deleteFinalizedAttachment(String fileName);
    List<PendingFinalizedFileResponse> getPendingFinalizedAttachments();
    Resource loadFile(String fileName);
    Resource loadPublicFile(String fileName);
}
