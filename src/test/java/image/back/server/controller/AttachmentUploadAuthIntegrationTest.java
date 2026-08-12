package image.back.server.controller;

import auth.common.core.context.VerifiedJwtPrincipalFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties =
        "app.jwt.secret=image-test-jwt-secret-hs512-minimum-length-64-chars-1234567890-extra")
class AttachmentUploadAuthIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterRegistrationBean<VerifiedJwtPrincipalFilter> attachmentJwtFilter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(attachmentJwtFilter.getFilter())
                .build();
    }

    @Test
    void uploadTempFile_forgedUserHeaderWithoutBearer_returnsUnauthorized() throws Exception {
        mockMvc.perform(multipart("/upload/temp-file")
                        .file(pdfFile())
                        .header("X-User-Key", "forged-user"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(4010200));
    }

    @Test
    void uploadTempImage_forgedUserHeaderWithoutBearer_returnsUnauthorized() throws Exception {
        mockMvc.perform(multipart("/upload/temp")
                        .file(new MockMultipartFile(
                                "file",
                                "artwork.jpg",
                                "image/jpeg",
                                new byte[]{1, 2, 3}
                        ))
                        .header("X-User-Key", "forged-user"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(4010200));
    }

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile(
                "file",
                "plan.pdf",
                "application/pdf",
                "%PDF-1.7\nattachment".getBytes(StandardCharsets.UTF_8)
        );
    }
}
