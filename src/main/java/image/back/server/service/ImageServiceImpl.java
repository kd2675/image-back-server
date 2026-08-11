package image.back.server.service;

import jakarta.annotation.PostConstruct;
import image.back.server.dto.ImageFileResponse;
import image.back.server.dto.PendingFinalizedFileResponse;
import image.back.server.dto.StoredFileResponse;
import image.back.server.exception.ImageNotFoundException;
import image.back.server.exception.InvalidFileException;
import image.back.server.exception.StorageException;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.scheduling.annotation.Scheduled;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class ImageServiceImpl implements ImageService {

    private static final Logger logger = LoggerFactory.getLogger(ImageServiceImpl.class);
    private static final String IMAGE_PREFIX = "/images/";
    private static final String TEMP_ROOT_DIR = "temp";
    private static final String TEMP_FILE_ROOT_DIR = "temp/files";
    private static final String FINALIZED_ATTACHMENT_ROOT_DIR = "semo/attachments";
    private static final String MODULE_DIRECTORY_NAME = "image-back-server";
    private static final String PENDING_CLAIM_SUFFIX = ".pending-claim";
    private static final String UPLOAD_TOKEN_SUFFIX = ".upload-token";
    private static final String FILE_PREFIX = "/files/";
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
    private static final Map<String, Set<String>> ATTACHMENT_CONTENT_TYPES = Map.ofEntries(
            Map.entry(".pdf", Set.of("application/pdf", "application/octet-stream")),
            Map.entry(".txt", Set.of("text/plain", "application/octet-stream")),
            Map.entry(".csv", Set.of("text/csv", "application/csv", "text/plain", "application/octet-stream")),
            Map.entry(".docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/zip", "application/octet-stream")),
            Map.entry(".xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/zip", "application/octet-stream")),
            Map.entry(".pptx", Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/zip", "application/octet-stream")),
            Map.entry(".hwp", Set.of("application/x-hwp", "application/haansofthwp", "application/octet-stream")),
            Map.entry(".hwpx", Set.of("application/vnd.hancom.hwpx", "application/zip", "application/octet-stream")),
            Map.entry(".zip", Set.of("application/zip", "application/x-zip-compressed", "application/octet-stream"))
    );

    @Value("${image.upload-dir}")
    private String uploadDir;

    @Value("${image.temp-retention-minutes:180}")
    private long tempRetentionMinutes;

    @Value("${attachment.max-size-bytes:20971520}")
    private long attachmentMaxSizeBytes;

    @Value("${attachment.orphan-grace-minutes:60}")
    private long attachmentOrphanGraceMinutes;

    private Path uploadRoot;
    private final SecureRandom secureRandom = new SecureRandom();

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
        return resolveUploadRoot(configuredUploadDir, Paths.get(""));
    }

    Path resolveUploadRoot(String configuredUploadDir, Path workingDirectory) {
        Path configuredPath = Paths.get(configuredUploadDir).normalize();
        if (configuredPath.isAbsolute()) {
            return configuredPath;
        }

        Path normalizedWorkingDirectory = workingDirectory.toAbsolutePath().normalize();
        Path workingDirectoryName = normalizedWorkingDirectory.getFileName();
        if (workingDirectoryName != null
                && configuredPath.getNameCount() > 0
                && workingDirectoryName.equals(configuredPath.getName(0))) {
            Path workspaceDirectory = normalizedWorkingDirectory.getParent();
            if (workspaceDirectory != null) {
                return workspaceDirectory.resolve(configuredPath).normalize();
            }
        }

        Path workingDirCandidate = normalizedWorkingDirectory.resolve(configuredPath).normalize();
        Path moduleDirCandidate = normalizedWorkingDirectory
                .resolve(MODULE_DIRECTORY_NAME)
                .resolve(configuredPath)
                .normalize();

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
            throw new InvalidFileException("Failed to store empty file.");
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
        if (!normalizedFileName.startsWith(TEMP_ROOT_DIR + "/")
                || normalizedFileName.startsWith(TEMP_FILE_ROOT_DIR + "/")) {
            throw new InvalidFileException("Only temporary image files can be finalized.");
        }

        String normalizedTargetDir = normalizeTargetDir(targetDir);
        Path sourceOriginalPath = resolveFilePath(normalizedFileName);
        String relativeWithoutTemp = normalizedFileName.substring((TEMP_ROOT_DIR + "/").length());
        String finalFileName = normalizedTargetDir + "/" + relativeWithoutTemp;
        if (!Files.exists(sourceOriginalPath)) {
            Path finalizedOriginalPath = resolveFilePath(finalFileName);
            if (Files.isRegularFile(finalizedOriginalPath)) {
                return toImageFileResponse(finalFileName, extractOriginalFileName(finalFileName), false, baseUrl);
            }
            throw new ImageNotFoundException("Temporary image not found: " + normalizedFileName);
        }

        try {
            moveAllVariants(normalizedFileName, finalFileName);
            logger.info("Finalized temp image: {} -> {}", normalizedFileName, finalFileName);
            return toImageFileResponse(finalFileName, extractOriginalFileName(finalFileName), false, baseUrl);
        } catch (IOException e) {
            throw new StorageException("Failed to finalize temporary image: " + normalizedFileName, e);
        }
    }

    @Override
    public StoredFileResponse storeTempFile(MultipartFile file, String baseUrl) {
        if (file == null || file.isEmpty()) {
            throw new StorageException("Failed to store empty file.");
        }
        if (file.getSize() > attachmentMaxSizeBytes) {
            throw new InvalidFileException("Attachment exceeds the maximum size of " + attachmentMaxSizeBytes + " bytes.");
        }

        String originalFileName = normalizeOriginalFileName(file.getOriginalFilename());
        String extension = extractAttachmentExtension(originalFileName);
        String contentType = normalizeAttachmentContentType(file.getContentType());
        validateAttachmentContentType(extension, contentType);

        LocalDate now = LocalDate.now();
        String relativeDir = TEMP_FILE_ROOT_DIR + "/" + now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String fileName = relativeDir + "/" + UUID.randomUUID() + extension;
        Path targetPath = resolveFilePath(fileName);

        try {
            Files.createDirectories(targetPath.getParent());
            try (var inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            validateAttachmentSignature(targetPath, extension);
            long storedSize = Files.size(targetPath);
            if (storedSize < 1 || storedSize > attachmentMaxSizeBytes) {
                Files.deleteIfExists(targetPath);
                throw new InvalidFileException("Stored attachment size is invalid.");
            }
            String uploadToken = createUploadToken(targetPath);
            logger.info("Stored temporary attachment: {} ({} bytes)", fileName, storedSize);
            return toStoredFileResponse(
                    fileName, originalFileName, contentType, storedSize, uploadToken, true, false, baseUrl
            );
        } catch (StorageException exception) {
            deleteQuietly(targetPath);
            deleteQuietly(uploadTokenPath(targetPath));
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(targetPath);
            deleteQuietly(uploadTokenPath(targetPath));
            throw new StorageException("Failed to store temporary attachment.", exception);
        }
    }

    @Override
    public StoredFileResponse finalizeTempFile(String fileName, String targetDir, String uploadToken, String baseUrl) {
        String normalizedFileName = normalizeFileName(fileName);
        if (!normalizedFileName.startsWith(TEMP_FILE_ROOT_DIR + "/")) {
            throw new InvalidFileException("Only temporary attachment files can be finalized.");
        }
        String normalizedTargetDir = normalizeTargetDir(targetDir);
        String relativeWithoutTemp = normalizedFileName.substring((TEMP_FILE_ROOT_DIR + "/").length());
        String finalFileName = normalizedTargetDir + "/" + relativeWithoutTemp;
        Path sourcePath = resolveFilePath(normalizedFileName);
        Path targetPath = resolveFilePath(finalFileName);
        Path sourceTokenPath = uploadTokenPath(sourcePath);
        Path targetTokenPath = uploadTokenPath(targetPath);
        validateUploadToken(Files.isRegularFile(sourcePath) ? sourceTokenPath : targetTokenPath, uploadToken);
        if (!Files.isRegularFile(sourcePath)) {
            if (Files.isRegularFile(targetPath)) {
                if (Files.isRegularFile(pendingClaimPath(targetPath))) {
                    throw new StorageException("Attachment finalization is already in progress: " + finalFileName);
                }
                String extension = extractAttachmentExtension(finalFileName);
                try {
                    return toStoredFileResponse(
                            finalFileName,
                            extractOriginalFileName(finalFileName),
                            resolveStoredAttachmentContentType(extension),
                            Files.size(targetPath),
                            null,
                            false,
                            false,
                            baseUrl
                    );
                } catch (IOException exception) {
                    throw new StorageException("Failed to inspect finalized attachment: " + finalFileName, exception);
                }
            }
            throw new ImageNotFoundException("Temporary attachment not found: " + normalizedFileName);
        }
        try {
            Files.createDirectories(targetPath.getParent());
            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            Files.move(sourceTokenPath, targetTokenPath, StandardCopyOption.REPLACE_EXISTING);
            createPendingClaim(targetPath);
            String extension = extractAttachmentExtension(finalFileName);
            String contentType = resolveStoredAttachmentContentType(extension);
            long sizeBytes = Files.size(targetPath);
            logger.info("Finalized temp attachment: {} -> {}", normalizedFileName, finalFileName);
            return toStoredFileResponse(
                    finalFileName,
                    extractOriginalFileName(finalFileName),
                    contentType,
                    sizeBytes,
                    null,
                    false,
                    true,
                    baseUrl
            );
        } catch (IOException exception) {
            restoreFinalizedAttachmentSource(sourcePath, targetPath);
            throw new StorageException("Failed to finalize temporary attachment: " + normalizedFileName, exception);
        }
    }

    @Override
    public boolean confirmFinalizedAttachment(String fileName) {
        Path attachmentPath = resolveFinalizedAttachmentPath(fileName);
        if (!Files.isRegularFile(attachmentPath)) {
            throw new ImageNotFoundException("Finalized attachment not found: " + fileName);
        }
        try {
            return Files.deleteIfExists(pendingClaimPath(attachmentPath));
        } catch (IOException exception) {
            throw new StorageException("Failed to confirm finalized attachment: " + fileName, exception);
        }
    }

    @Override
    public boolean deleteFinalizedAttachment(String fileName) {
        Path attachmentPath = resolveFinalizedAttachmentPath(fileName);
        try {
            boolean deleted = Files.deleteIfExists(attachmentPath);
            Files.deleteIfExists(pendingClaimPath(attachmentPath));
            Files.deleteIfExists(uploadTokenPath(attachmentPath));
            deleteEmptyParents(attachmentPath.getParent(), uploadRoot.resolve(FINALIZED_ATTACHMENT_ROOT_DIR));
            return deleted;
        } catch (IOException exception) {
            throw new StorageException("Failed to delete finalized attachment: " + fileName, exception);
        }
    }

    @Override
    public List<PendingFinalizedFileResponse> getPendingFinalizedAttachments() {
        Path attachmentRoot = uploadRoot.resolve(FINALIZED_ATTACHMENT_ROOT_DIR).normalize();
        if (!Files.isDirectory(attachmentRoot)) {
            return List.of();
        }
        LocalDateTime threshold = LocalDateTime.now()
                .minus(attachmentOrphanGraceMinutes, ChronoUnit.MINUTES);
        try (Stream<Path> walk = Files.walk(attachmentRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(this::isPendingClaim)
                    .map(path -> toPendingFinalizedFile(path, threshold))
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(PendingFinalizedFileResponse::finalizedAt))
                    .toList();
        } catch (IOException exception) {
            throw new StorageException("Failed to inspect pending finalized attachments.", exception);
        }
    }

    @Override
    public Resource loadFile(String fileName) {
        String normalizedFileName = normalizeFileName(fileName);
        Path filePath = resolveFilePath(normalizedFileName);
        try {
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable() || !Files.isRegularFile(filePath)) {
                throw new ImageNotFoundException("Could not read attachment: " + normalizedFileName);
            }
            return resource;
        } catch (MalformedURLException exception) {
            throw new ImageNotFoundException("Could not read attachment: " + normalizedFileName, exception);
        }
    }

    @Override
    public Resource loadPublicFile(String fileName) {
        String normalizedFileName = normalizeFileName(fileName);
        if (normalizedFileName.startsWith(FINALIZED_ATTACHMENT_ROOT_DIR + "/")
                || normalizedFileName.startsWith(TEMP_FILE_ROOT_DIR + "/")) {
            throw new ImageNotFoundException("Could not read attachment: " + normalizedFileName);
        }
        return loadFile(normalizedFileName);
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

    private void createPendingClaim(Path attachmentPath) throws IOException {
        Files.writeString(
                pendingClaimPath(attachmentPath),
                attachmentPath.getFileName().toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
    }

    private String createUploadToken(Path attachmentPath) throws IOException {
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String token = java.util.HexFormat.of().formatHex(tokenBytes);
        Files.writeString(
                uploadTokenPath(attachmentPath),
                hashUploadToken(token),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
        return token;
    }

    private void validateUploadToken(Path tokenPath, String providedToken) {
        if (providedToken == null || providedToken.isBlank() || !Files.isRegularFile(tokenPath)) {
            throw new InvalidFileException("Temporary attachment ownership token is invalid.");
        }
        try {
            byte[] expected = Files.readString(tokenPath).trim().getBytes(StandardCharsets.UTF_8);
            byte[] provided = hashUploadToken(providedToken).getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expected, provided)) {
                throw new InvalidFileException("Temporary attachment ownership token is invalid.");
            }
        } catch (IOException exception) {
            throw new StorageException("Failed to validate temporary attachment ownership.", exception);
        }
    }

    private String hashUploadToken(String token) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void restoreFinalizedAttachmentSource(Path sourcePath, Path targetPath) {
        try {
            Files.deleteIfExists(pendingClaimPath(targetPath));
            if (Files.isRegularFile(targetPath) && !Files.exists(sourcePath)) {
                Files.createDirectories(sourcePath.getParent());
                Files.move(targetPath, sourcePath, StandardCopyOption.REPLACE_EXISTING);
            }
            Path targetTokenPath = uploadTokenPath(targetPath);
            if (Files.isRegularFile(targetTokenPath) && !Files.exists(uploadTokenPath(sourcePath))) {
                Files.move(targetTokenPath, uploadTokenPath(sourcePath), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException restoreException) {
            logger.warn("Failed to restore attachment after finalization error: {}", targetPath, restoreException);
        }
    }

    private Path resolveFinalizedAttachmentPath(String fileName) {
        String normalizedFileName = normalizeFileName(fileName);
        if (!normalizedFileName.startsWith(FINALIZED_ATTACHMENT_ROOT_DIR + "/")
                || normalizedFileName.endsWith(PENDING_CLAIM_SUFFIX)
                || normalizedFileName.contains("..")
                || normalizedFileName.contains("\\")) {
            throw new InvalidFileException("Finalized attachment fileName is invalid");
        }
        extractAttachmentExtension(normalizedFileName);
        return resolveFilePath(normalizedFileName);
    }

    private Path pendingClaimPath(Path attachmentPath) {
        return attachmentPath.resolveSibling(attachmentPath.getFileName() + PENDING_CLAIM_SUFFIX);
    }

    private Path uploadTokenPath(Path attachmentPath) {
        return attachmentPath.resolveSibling(attachmentPath.getFileName() + UPLOAD_TOKEN_SUFFIX);
    }

    private boolean isPendingClaim(Path path) {
        return path.getFileName().toString().endsWith(PENDING_CLAIM_SUFFIX);
    }

    private PendingFinalizedFileResponse toPendingFinalizedFile(Path claimPath, LocalDateTime threshold) {
        try {
            LocalDateTime finalizedAt = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(claimPath).toInstant(),
                    ZoneOffset.systemDefault()
            );
            if (finalizedAt.isAfter(threshold)) {
                return null;
            }
            String claimRelativePath = uploadRoot.relativize(claimPath).toString().replace('\\', '/');
            String fileName = claimRelativePath.substring(0, claimRelativePath.length() - PENDING_CLAIM_SUFFIX.length());
            return new PendingFinalizedFileResponse(fileName, finalizedAt);
        } catch (IOException exception) {
            logger.warn("Failed to inspect pending attachment claim: {}", claimPath, exception);
            return null;
        }
    }

    private void deleteEmptyParents(Path start, Path stopInclusive) throws IOException {
        Path normalizedStop = stopInclusive.toAbsolutePath().normalize();
        Path current = start;
        while (current != null && current.toAbsolutePath().normalize().startsWith(normalizedStop)) {
            try (Stream<Path> children = Files.list(current)) {
                if (children.findAny().isPresent()) {
                    return;
                }
            }
            Files.deleteIfExists(current);
            if (current.toAbsolutePath().normalize().equals(normalizedStop)) {
                return;
            }
            current = current.getParent();
        }
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
            throw new InvalidFileException("fileName is required");
        }
        String normalized = fileName.trim().replace("\\", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private String normalizeTargetDir(String targetDir) {
        if (targetDir == null || targetDir.isBlank()) {
            throw new InvalidFileException("targetDir is required");
        }
        String normalized = targetDir.trim().replace("\\", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()
                || normalized.contains("..")
                || normalized.startsWith(TEMP_ROOT_DIR + "/")
                || !normalized.matches("[A-Za-z0-9][A-Za-z0-9/_-]*")) {
            throw new InvalidFileException("targetDir is invalid");
        }
        return normalized;
    }

    private String normalizeOriginalFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            throw new InvalidFileException("Original file name is required.");
        }
        String normalized = originalFileName.trim().replace("\\", "/");
        int slashIndex = normalized.lastIndexOf('/');
        String baseName = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
        if (baseName.isBlank() || baseName.length() > 255 || baseName.contains("\r") || baseName.contains("\n")) {
            throw new InvalidFileException("Original file name is invalid.");
        }
        return baseName;
    }

    private String extractAttachmentExtension(String fileName) {
        int extensionIndex = fileName.lastIndexOf('.');
        if (extensionIndex < 1 || extensionIndex == fileName.length() - 1) {
            throw new InvalidFileException("Attachment file extension is required.");
        }
        String extension = fileName.substring(extensionIndex).toLowerCase(Locale.ROOT);
        if (!ATTACHMENT_CONTENT_TYPES.containsKey(extension)) {
            throw new InvalidFileException("Unsupported attachment file extension: " + extension);
        }
        return extension;
    }

    private String normalizeAttachmentContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        int parameterIndex = contentType.indexOf(';');
        return (parameterIndex < 0 ? contentType : contentType.substring(0, parameterIndex))
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private void validateAttachmentContentType(String extension, String contentType) {
        if (!ATTACHMENT_CONTENT_TYPES.get(extension).contains(contentType)) {
            throw new InvalidFileException("Attachment content type does not match its extension.");
        }
    }

    private void validateAttachmentSignature(Path path, String extension) throws IOException {
        byte[] prefix = new byte[8];
        int read;
        try (var inputStream = Files.newInputStream(path, StandardOpenOption.READ)) {
            read = inputStream.read(prefix);
        }
        boolean valid = switch (extension) {
            case ".pdf" -> read >= 5
                    && prefix[0] == '%'
                    && prefix[1] == 'P'
                    && prefix[2] == 'D'
                    && prefix[3] == 'F'
                    && prefix[4] == '-';
            case ".docx", ".xlsx", ".pptx", ".hwpx", ".zip" -> read >= 4
                    && prefix[0] == 'P'
                    && prefix[1] == 'K';
            case ".hwp" -> read >= 8
                    && prefix[0] == (byte) 0xD0
                    && prefix[1] == (byte) 0xCF
                    && prefix[2] == 0x11
                    && prefix[3] == (byte) 0xE0
                    && prefix[4] == (byte) 0xA1
                    && prefix[5] == (byte) 0xB1
                    && prefix[6] == 0x1A
                    && prefix[7] == (byte) 0xE1;
            case ".txt", ".csv" -> read > 0 && !containsNullByte(prefix, read);
            default -> false;
        };
        if (!valid) {
            throw new InvalidFileException("Attachment file signature is invalid.");
        }
    }

    private boolean containsNullByte(byte[] bytes, int length) {
        for (int index = 0; index < length; index++) {
            if (bytes[index] == 0) {
                return true;
            }
        }
        return false;
    }

    private String resolveStoredAttachmentContentType(String extension) {
        return switch (extension) {
            case ".pdf" -> "application/pdf";
            case ".txt" -> "text/plain";
            case ".csv" -> "text/csv";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case ".hwp" -> "application/x-hwp";
            case ".hwpx" -> "application/vnd.hancom.hwpx";
            case ".zip" -> "application/zip";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }

    private StoredFileResponse toStoredFileResponse(
            String fileName,
            String originalFileName,
            String contentType,
            long sizeBytes,
            String uploadToken,
            boolean temporary,
            boolean newlyFinalized,
            String baseUrl
    ) {
        return new StoredFileResponse(
                fileName,
                originalFileName,
                contentType,
                sizeBytes,
                null,
                uploadToken,
                temporary,
                newlyFinalized
        );
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            logger.warn("Failed to cleanup attachment after an error: {}", path, exception);
        }
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
