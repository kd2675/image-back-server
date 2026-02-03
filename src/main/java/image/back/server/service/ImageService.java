package image.back.server.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface ImageService {
    String storeImage(MultipartFile file);
    Resource loadImage(String year, String month, String day, String filename, Integer width, Integer height);
}
