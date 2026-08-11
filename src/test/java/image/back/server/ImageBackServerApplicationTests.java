package image.back.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties =
        "app.jwt.secret=image-test-jwt-secret-hs512-minimum-length-64-chars-1234567890-extra")
class ImageBackServerApplicationTests {

	@Test
	void contextLoads() {
	}

}
