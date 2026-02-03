package image.back.server.controller;

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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "Image", description = "이미지 업로드 및 조회 API")
@RestController
public class ImageController {

    private final ImageService imageService;

    public ImageController(ImageService imageService) {
        this.imageService = imageService;
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

    @Operation(summary = "이미지 조회", description = "지정된 경로의 이미지를 조회합니다. width와 height 파라미터를 통해 동적으로 이미지 크기를 조절할 수 있습니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "이미지 조회 성공", content = @Content(mediaType = "image/*")),
            @ApiResponse(responseCode = "404", description = "이미지를 찾을 수 없음")
    })
    @GetMapping("/images/{year}/{month}/{day}/{filename:.+}")
    public ResponseEntity<Resource> getImage(
            @Parameter(description = "년", example = "2024") @PathVariable String year,
            @Parameter(description = "월", example = "02") @PathVariable String month,
            @Parameter(description = "일", example = "03") @PathVariable String day,
            @Parameter(description = "파일 이름", example = "image.jpg") @PathVariable String filename,
            @Parameter(description = "원하는 이미지 너비 (px)") @RequestParam(required = false) Integer width,
            @Parameter(description = "원하는 이미지 높이 (px)") @RequestParam(required = false) Integer height) {
        Resource resource = imageService.loadImage(year, month, day, filename, width, height);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                .body(resource);
    }
}

