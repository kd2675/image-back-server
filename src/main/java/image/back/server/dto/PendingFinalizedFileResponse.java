package image.back.server.dto;

import java.time.LocalDateTime;

public record PendingFinalizedFileResponse(
        String fileName,
        LocalDateTime finalizedAt
) {
}
