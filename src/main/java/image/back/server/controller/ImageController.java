package image.back.server.controller;

import image.back.server.dto.ImageFileResponse;
import image.back.server.dto.ImageFinalizeRequest;
import image.back.server.dto.FileFinalizeRequest;
import image.back.server.dto.PendingFinalizedFileResponse;
import image.back.server.dto.StoredFileResponse;
import image.back.server.exception.InvalidFileException;
import image.back.server.service.ImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.stream.Collectors;

@Tag(name = "Image", description = "이미지 업로드 및 조회 API")
@RestController
public class ImageController {

    private final ImageService imageService;
    private final byte[] internalFileToken;

    public ImageController(
            ImageService imageService,
            @Value("${attachment.internal-token}") String internalFileToken
    ) {
        this.imageService = imageService;
        if (internalFileToken == null || internalFileToken.isBlank()) {
            throw new IllegalStateException("attachment.internal-token must be configured");
        }
        this.internalFileToken = internalFileToken.getBytes(StandardCharsets.UTF_8);
    }

    @Schema(description = "일괄 업로드 응답")
    public record BatchUploadResponse(
            @Schema(description = "원본 파일명") String originalFilename,
            @Schema(description = "저장된 파일 경로 (성공 시)") String url,
            @Schema(description = "처리 성공 여부") boolean success,
            @Schema(description = "에러 메시지 (실패 시)") String error
    ) {}

    @Operation(summary = "이미지 일괄 업로드", description = "여러 이미지를 한 번에 업로드합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "일괄 업로드 처리 완료", content = @Content(schema = @Schema(implementation = BatchUploadResponse.class)))
    })
    @PostMapping(value = "/upload/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<BatchUploadResponse>> uploadImageBatch(
            @Parameter(description = "업로드할 이미지 파일 목록", required = true) @RequestParam("files") List<MultipartFile> files) {

        List<BatchUploadResponse> responses = files.stream()
                .map(file -> {
                    if (file.isEmpty()) {
                        return new BatchUploadResponse(file.getOriginalFilename(), null, false, "File is empty");
                    }
                    try {
                        String fileDownloadUri = imageService.storeImage(file);
                        return new BatchUploadResponse(file.getOriginalFilename(), fileDownloadUri, true, null);
                    } catch (Exception e) {
                        return new BatchUploadResponse(file.getOriginalFilename(), null, false, e.getMessage());
                    }
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "이미지 업로드", description = "이미지를 업로드하고 원본, 썸네일 및 여러 크기의 이미지를 생성합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "이미지 업로드 성공", content = @Content(schema = @Schema(implementation = String.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> uploadImage(
            @Parameter(description = "업로드할 이미지 파일", required = true) @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Please select a file to upload");
        }
        String fileDownloadUri = imageService.storeImage(file);
        return ResponseEntity.ok().body("File uploaded successfully: " + fileDownloadUri);
    }

    @PostMapping(value = "/upload/temp", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageFileResponse> uploadTempImage(
            @RequestParam("file") MultipartFile file
    ) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(
                imageService.storeTempImage(file, currentBaseUrl())
        );
    }

    @PostMapping("/files/finalize")
    public ResponseEntity<ImageFileResponse> finalizeImage(
            @RequestBody ImageFinalizeRequest request
    ) {
        return ResponseEntity.ok(
                imageService.finalizeTempImage(
                        request.fileName(),
                        request.targetDir(),
                        currentBaseUrl()
                )
        );
    }

    @PostMapping(value = "/upload/temp-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StoredFileResponse> uploadTempFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(imageService.storeTempFile(file, currentBaseUrl()));
    }

    @PostMapping("/files/finalize-attachment")
    public ResponseEntity<StoredFileResponse> finalizeAttachment(
            @RequestBody FileFinalizeRequest request,
            @RequestHeader("X-Internal-File-Token") String internalToken
    ) {
        requireInternalToken(internalToken);
        return ResponseEntity.ok(
                imageService.finalizeTempFile(request.fileName(), request.targetDir(), currentBaseUrl())
        );
    }

    @PostMapping("/internal/files/confirm-attachment")
    public ResponseEntity<Void> confirmFinalizedAttachment(
            @RequestBody FileFinalizeRequest request,
            @RequestHeader("X-Internal-File-Token") String internalToken
    ) {
        requireInternalToken(internalToken);
        imageService.confirmFinalizedAttachment(request.fileName());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/internal/files/finalized-attachment")
    public ResponseEntity<Void> deleteFinalizedAttachment(
            @RequestParam String fileName,
            @RequestHeader("X-Internal-File-Token") String internalToken
    ) {
        requireInternalToken(internalToken);
        imageService.deleteFinalizedAttachment(fileName);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/internal/files/pending-attachments")
    public ResponseEntity<List<PendingFinalizedFileResponse>> getPendingFinalizedAttachments(
            @RequestHeader("X-Internal-File-Token") String internalToken
    ) {
        requireInternalToken(internalToken);
        return ResponseEntity.ok(imageService.getPendingFinalizedAttachments());
    }

    @GetMapping("/internal/files/content")
    public ResponseEntity<Resource> getInternalFile(
            @RequestParam String fileName,
            @RequestHeader("X-Internal-File-Token") String internalToken
    ) {
        requireInternalToken(internalToken);
        Resource resource = imageService.loadFile(fileName);
        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping("/files/{*fileName}")
    public ResponseEntity<Resource> getFile(
            @PathVariable String fileName,
            @RequestParam(required = false) String downloadName
    ) {
        Resource resource = imageService.loadPublicFile(fileName);
        String resolvedDownloadName = downloadName == null || downloadName.isBlank()
                ? resource.getFilename()
                : downloadName.replace("\r", "").replace("\n", "").trim();
        if (resolvedDownloadName != null && resolvedDownloadName.length() > 255) {
            resolvedDownloadName = resolvedDownloadName.substring(0, 255);
        }
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(resolvedDownloadName == null ? "attachment" : resolvedDownloadName, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @Operation(summary = "이미지 조회", description = "지정된 경로의 이미지를 조회합니다. width와 height 파라미터를 통해 동적으로 이미지 크기를 조절할 수 있습니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "이미지 조회 성공", content = @Content(mediaType = "image/*")),
            @ApiResponse(responseCode = "404", description = "이미지를 찾을 수 없음")
    })
    @GetMapping("/images/{*fileName}")
    public ResponseEntity<Resource> getImage(
            @Parameter(description = "저장된 파일 이름", example = "temp/2026/03/03/example.jpg")
            @PathVariable String fileName,
            @Parameter(description = "원하는 이미지 너비 (px)") @RequestParam(required = false) Integer width,
            @Parameter(description = "원하는 이미지 높이 (px)") @RequestParam(required = false) Integer height) {
        Resource resource = imageService.loadImage(fileName, width, height);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM))
                .body(resource);
    }

    private String currentBaseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }

    private void requireInternalToken(String providedToken) {
        byte[] provided = providedToken == null
                ? new byte[0]
                : providedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(internalFileToken, provided)) {
            throw new InvalidFileException("Internal file request is not authorized.");
        }
    }
}
