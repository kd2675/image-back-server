package image.back.server.service;

import jakarta.annotation.PostConstruct;
import image.back.server.dto.ImageFileResponse;
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
import org.springframework.scheduling.annotation.Scheduled;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class ImageServiceImpl implements ImageService {

    private static final Logger logger = LoggerFactory.getLogger(ImageServiceImpl.class);
    private static final String IMAGE_PREFIX = "/images/";
    private static final String TEMP_ROOT_DIR = "temp";
    private static final String THUMB_SUFFIX = "_thumb";
    private static final String SMALL_SUFFIX = "_small";
    private static final String MEDIUM_SUFFIX = "_medium";
    private static final String LARGE_SUFFIX = "_large";
    private static final String[] VARIANT_SUFFIXES = {
            "",
            THUMB_SUFFIX,
            SMALL_SUFFIX,
            MEDIUM_SUFFIX,
            LARGE_SUFFIX
    };

    @Value("${image.upload-dir}")
    private String uploadDir;

    @Value("${image.temp-retention-minutes:180}")
    private long tempRetentionMinutes;

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
            StoredImage storedImage = storeInternal(file, null);
            return storedImage.fileName();
        } catch (IOException e) {
            throw new StorageException("Failed to store file.", e);
        }
    }

    @Override
    public ImageFileResponse storeTempImage(MultipartFile file, String baseUrl) {
        if (file.isEmpty()) {
            throw new StorageException("Failed to store empty file.");
        }
        try {
            StoredImage storedImage = storeInternal(file, TEMP_ROOT_DIR);
            return toImageFileResponse(
                    storedImage.fileName(),
                    storedImage.originalFileName(),
                    true,
                    baseUrl
            );
        } catch (IOException e) {
            throw new StorageException("Failed to store temporary file.", e);
        }
    }

    @Override
    public ImageFileResponse finalizeTempImage(String fileName, String targetDir, String baseUrl) {
        String normalizedFileName = normalizeFileName(fileName);
        if (!normalizedFileName.startsWith(TEMP_ROOT_DIR + "/")) {
            throw new StorageException("Only temporary files can be finalized.");
        }

        String normalizedTargetDir = normalizeTargetDir(targetDir);
        Path sourceOriginalPath = resolveFilePath(normalizedFileName);
        if (!Files.exists(sourceOriginalPath)) {
            throw new ImageNotFoundException("Temporary image not found: " + normalizedFileName);
        }

        String relativeWithoutTemp = normalizedFileName.substring((TEMP_ROOT_DIR + "/").length());
        String finalFileName = normalizedTargetDir + "/" + relativeWithoutTemp;

        try {
            moveAllVariants(normalizedFileName, finalFileName);
            logger.info("Finalized temp image: {} -> {}", normalizedFileName, finalFileName);
            return toImageFileResponse(finalFileName, extractOriginalFileName(finalFileName), false, baseUrl);
        } catch (IOException e) {
            throw new StorageException("Failed to finalize temporary image: " + normalizedFileName, e);
        }
    }

    @Override
    public Resource loadImage(String fileName, Integer width, Integer height) {
        try {
            String normalizedFileName = normalizeFileName(fileName);
            Path originalFilePath = resolveFilePath(normalizedFileName);

            // 동적 리사이징이 필요 없는 경우 원본 반환
            if (width == null || height == null) {
                logger.info("Loading original image: {}", originalFilePath);
                Resource resource = new UrlResource(originalFilePath.toUri());
                if (resource.exists() && resource.isReadable()) {
                    return resource;
                } else {
                    throw new ImageNotFoundException("Could not read file: " + normalizedFileName);
                }
            }

            // 동적 리사이징 처리
            int extensionIndex = normalizedFileName.lastIndexOf('.');
            String baseFilename = normalizedFileName.substring(0, extensionIndex);
            String fileExtension = normalizedFileName.substring(extensionIndex);
            String resizedFileName = baseFilename + "_" + width + "x" + height + fileExtension;
            Path resizedFilePath = resolveFilePath(resizedFileName);

            if (Files.exists(resizedFilePath)) {
                logger.info("Loading pre-resized image: {}", resizedFilePath);
                return new UrlResource(resizedFilePath.toUri());
            } else {
                logger.info("Dynamically resizing image to {}x{}", width, height);
                Resource originalResource = new UrlResource(originalFilePath.toUri());
                if (!originalResource.exists() || !originalResource.isReadable()) {
                    throw new ImageNotFoundException("Could not find original file for resizing: " + normalizedFileName);
                }

                try (var inputStream = originalResource.getInputStream()) {
                    BufferedImage originalImage = ImageIO.read(inputStream);
                    Thumbnails.of(originalImage)
                            .size(width, height)
                            .toFile(resizedFilePath.toFile());
                    logger.info("Saved dynamically resized image: {}", resizedFilePath);
                    return new UrlResource(resizedFilePath.toUri());
                } catch (IOException e) {
                    throw new StorageException("Could not create resized image for file: " + normalizedFileName, e);
                }
            }

        } catch (MalformedURLException e) {
            throw new ImageNotFoundException("Could not read file: " + fileName, e);
        }
    }

    @Scheduled(fixedDelayString = "${image.temp-cleanup-delay-ms:3600000}")
    public void cleanupTemporaryFiles() {
        Path tempRoot = uploadRoot.resolve(TEMP_ROOT_DIR).normalize();
        if (!Files.exists(tempRoot)) {
            return;
        }

        LocalDateTime threshold = LocalDateTime.now().minus(tempRetentionMinutes, ChronoUnit.MINUTES);

        try (Stream<Path> walk = Files.walk(tempRoot)) {
            walk.filter(Files::isRegularFile)
                    .forEach(path -> deleteIfExpired(path, threshold));
        } catch (IOException e) {
            logger.warn("Failed to scan temp upload root for cleanup", e);
        }

        try (Stream<Path> walk = Files.walk(tempRoot)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(tempRoot))
                    .filter(Files::isDirectory)
                    .forEach(this::deleteIfEmptyDirectory);
        } catch (IOException e) {
            logger.warn("Failed to cleanup empty temp directories", e);
        }
    }

    private StoredImage storeInternal(MultipartFile file, String rootDir) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String fileExtension = extractFileExtension(originalFilename);
        String imageFormat = resolveImageFormat(fileExtension);
        String newBaseFilename = UUID.randomUUID().toString();

        LocalDate now = LocalDate.now();
        String datePath = now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String relativeDir = rootDir == null ? datePath : rootDir + "/" + datePath;
        Path uploadPath = uploadRoot.resolve(relativeDir).normalize();

        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        try (var inputStream = file.getInputStream()) {
            BufferedImage originalImage = ImageIO.read(inputStream);
            if (originalImage == null) {
                throw new StorageException("Failed to read image file.");
            }
            writeAllVariants(uploadPath, newBaseFilename, fileExtension, imageFormat, originalImage);
        }

        String fileName = relativeDir + "/" + newBaseFilename + fileExtension;
        logger.info("Stored image: {}", fileName);
        return new StoredImage(fileName, originalFilename == null ? newBaseFilename + fileExtension : originalFilename);
    }

    private void writeAllVariants(
            Path uploadPath,
            String baseFileName,
            String fileExtension,
            String imageFormat,
            BufferedImage originalImage
    ) throws IOException {
        Path originalFilePath = uploadPath.resolve(baseFileName + fileExtension);
        ImageIO.write(originalImage, imageFormat, originalFilePath.toFile());

        Path thumbFilePath = uploadPath.resolve(baseFileName + THUMB_SUFFIX + fileExtension);
        Thumbnails.of(originalImage).size(150, 150).toFile(thumbFilePath.toFile());

        Path smallFilePath = uploadPath.resolve(baseFileName + SMALL_SUFFIX + fileExtension);
        Thumbnails.of(originalImage).size(320, 240).toFile(smallFilePath.toFile());

        Path mediumFilePath = uploadPath.resolve(baseFileName + MEDIUM_SUFFIX + fileExtension);
        Thumbnails.of(originalImage).size(640, 480).toFile(mediumFilePath.toFile());

        Path largeFilePath = uploadPath.resolve(baseFileName + LARGE_SUFFIX + fileExtension);
        Thumbnails.of(originalImage).size(1024, 768).toFile(largeFilePath.toFile());
    }

    private void moveAllVariants(String sourceFileName, String targetFileName) throws IOException {
        for (String suffix : VARIANT_SUFFIXES) {
            Path sourcePath = resolveFilePath(applyVariantSuffix(sourceFileName, suffix));
            if (!Files.exists(sourcePath)) {
                continue;
            }

            Path targetPath = resolveFilePath(applyVariantSuffix(targetFileName, suffix));
            if (!Files.exists(targetPath.getParent())) {
                Files.createDirectories(targetPath.getParent());
            }
            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String normalizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new StorageException("fileName is required");
        }
        String normalized = fileName.trim().replace("\\", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private String normalizeTargetDir(String targetDir) {
        if (targetDir == null || targetDir.isBlank()) {
            throw new StorageException("targetDir is required");
        }
        String normalized = targetDir.trim().replace("\\", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.contains("..")) {
            throw new StorageException("targetDir is invalid");
        }
        return normalized;
    }

    private Path resolveFilePath(String fileName) {
        Path resolved = uploadRoot.resolve(fileName).normalize();
        if (!resolved.startsWith(uploadRoot.toAbsolutePath().normalize())) {
            throw new StorageException("Invalid fileName: " + fileName);
        }
        return resolved;
    }

    private String extractFileExtension(String originalFilename) {
        if (originalFilename == null || !originalFilename.contains(".")) {
            throw new StorageException("Image file extension is required.");
        }
        return originalFilename.substring(originalFilename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
    }

    private String resolveImageFormat(String fileExtension) {
        String format = fileExtension.startsWith(".") ? fileExtension.substring(1) : fileExtension;
        if ("jpg".equalsIgnoreCase(format)) {
            return "jpeg";
        }
        return format;
    }

    private ImageFileResponse toImageFileResponse(
            String fileName,
            String originalFileName,
            boolean temporary,
            String baseUrl
    ) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        return new ImageFileResponse(
                fileName,
                originalFileName,
                normalizedBaseUrl + IMAGE_PREFIX + fileName,
                normalizedBaseUrl + IMAGE_PREFIX + applyVariantSuffix(fileName, THUMB_SUFFIX),
                temporary
        );
    }

    private String applyVariantSuffix(String fileName, String suffix) {
        int extensionIndex = fileName.lastIndexOf('.');
        if (extensionIndex < 0) {
            return fileName + suffix;
        }
        String baseName = fileName.substring(0, extensionIndex);
        String extension = fileName.substring(extensionIndex);
        return baseName + suffix + extension;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String extractOriginalFileName(String fileName) {
        int slashIndex = fileName.lastIndexOf('/');
        return slashIndex >= 0 ? fileName.substring(slashIndex + 1) : fileName;
    }

    private void deleteIfExpired(Path path, LocalDateTime threshold) {
        try {
            LocalDateTime lastModifiedTime = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(path).toInstant(),
                    ZoneOffset.systemDefault()
            );
            if (lastModifiedTime.isBefore(threshold)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            logger.warn("Failed to delete expired temp image: {}", path, e);
        }
    }

    private void deleteIfEmptyDirectory(Path path) {
        try (Stream<Path> children = Files.list(path)) {
            if (children.findAny().isEmpty()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            logger.warn("Failed to cleanup temp directory: {}", path, e);
        }
    }

    private record StoredImage(String fileName, String originalFileName) {
    }
}
