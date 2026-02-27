package image.back.server.service;

import jakarta.annotation.PostConstruct;
import image.back.server.exception.ImageNotFoundException;
import image.back.server.exception.StorageException;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class ImageServiceImpl implements ImageService {

    private static final Logger logger = LoggerFactory.getLogger(ImageServiceImpl.class);

    @Value("${image.upload-dir}")
    private String uploadDir;
    private Path uploadRoot;

    @PostConstruct
    public void initializeUploadRoot() {
        this.uploadRoot = resolveUploadRoot(uploadDir);
        logger.info(
                "Image upload root resolved. configured='{}', resolved='{}'",
                uploadDir,
                uploadRoot.toAbsolutePath().normalize()
        );
    }

    private Path resolveUploadRoot(String configuredUploadDir) {
        Path configuredPath = Paths.get(configuredUploadDir).normalize();
        if (configuredPath.isAbsolute()) {
            return configuredPath;
        }

        Path workingDirCandidate = configuredPath.toAbsolutePath().normalize();
        Path moduleDirCandidate = Paths.get("image-back-server").resolve(configuredPath).toAbsolutePath().normalize();

        if (Files.exists(workingDirCandidate)) {
            return workingDirCandidate;
        }
        if (Files.exists(moduleDirCandidate)) {
            return moduleDirCandidate;
        }
        return workingDirCandidate;
    }

    @Override
    public String storeImage(MultipartFile file) {
        if (file.isEmpty()) {
            throw new StorageException("Failed to store empty file.");
        }

        try {
            String originalFilename = file.getOriginalFilename();
            String fileExtension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                fileExtension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String newBaseFilename = UUID.randomUUID().toString();

            LocalDate now = LocalDate.now();
            String datePath = now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            Path uploadPath = uploadRoot.resolve(datePath).normalize();

            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            BufferedImage originalImage = ImageIO.read(file.getInputStream());

            // Save original image
            Path originalFilePath = uploadPath.resolve(newBaseFilename + fileExtension);
            ImageIO.write(originalImage, fileExtension.substring(1), originalFilePath.toFile());
            logger.info("Stored original image: {}", originalFilePath);

            // Create and save thumbnail
            Path thumbFilePath = uploadPath.resolve(newBaseFilename + "_thumb" + fileExtension);
            Thumbnails.of(originalImage).size(150, 150).toFile(thumbFilePath.toFile());

            // Create and save small image
            Path smallFilePath = uploadPath.resolve(newBaseFilename + "_small" + fileExtension);
            Thumbnails.of(originalImage).size(320, 240).toFile(smallFilePath.toFile());

            // Create and save medium image
            Path mediumFilePath = uploadPath.resolve(newBaseFilename + "_medium" + fileExtension);
            Thumbnails.of(originalImage).size(640, 480).toFile(mediumFilePath.toFile());

            // Create and save large image
            Path largeFilePath = uploadPath.resolve(newBaseFilename + "_large" + fileExtension);
            Thumbnails.of(originalImage).size(1024, 768).toFile(largeFilePath.toFile());
            
            return datePath + "/" + newBaseFilename + fileExtension;
        } catch (IOException e) {
            throw new StorageException("Failed to store file.", e);
        }
    }

    @Override
    public Resource loadImage(String year, String month, String day, String filename, Integer width, Integer height) {
        try {
            String datePath = year + "/" + month + "/" + day;
            Path originalFilePath = uploadRoot.resolve(datePath).resolve(filename).normalize();
            
            // 동적 리사이징이 필요 없는 경우 원본 반환
            if (width == null || height == null) {
                logger.info("Loading original image: {}", originalFilePath);
                Resource resource = new UrlResource(originalFilePath.toUri());
                if (resource.exists() && resource.isReadable()) {
                    return resource;
                } else {
                    throw new ImageNotFoundException("Could not read file: " + filename);
                }
            }

            // 동적 리사이징 처리
            String baseFilename = filename.substring(0, filename.lastIndexOf('.'));
            String fileExtension = filename.substring(filename.lastIndexOf('.'));
            String resizedFilename = baseFilename + "_" + width + "x" + height + fileExtension;
            Path resizedFilePath = uploadRoot.resolve(datePath).resolve(resizedFilename).normalize();

            if (Files.exists(resizedFilePath)) {
                logger.info("Loading pre-resized image: {}", resizedFilePath);
                return new UrlResource(resizedFilePath.toUri());
            } else {
                logger.info("Dynamically resizing image to {}x{}", width, height);
                Resource originalResource = new UrlResource(originalFilePath.toUri());
                if (!originalResource.exists() || !originalResource.isReadable()) {
                    throw new ImageNotFoundException("Could not find original file for resizing: " + filename);
                }

                try (var inputStream = originalResource.getInputStream()) {
                    BufferedImage originalImage = ImageIO.read(inputStream);
                    Thumbnails.of(originalImage)
                            .size(width, height)
                            .toFile(resizedFilePath.toFile());
                    logger.info("Saved dynamically resized image: {}", resizedFilePath);
                    return new UrlResource(resizedFilePath.toUri());
                } catch (IOException e) {
                    throw new StorageException("Could not create resized image for file: " + filename, e);
                }
            }

        } catch (MalformedURLException e) {
            throw new ImageNotFoundException("Could not read file: " + filename, e);
        }
    }
}
