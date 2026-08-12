package image.back.server.config;

import auth.common.core.context.VerifiedJwtPrincipalFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class AttachmentAuthConfig {
    @Bean
    public FilterRegistrationBean<VerifiedJwtPrincipalFilter> attachmentJwtFilter(
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.security.issuer}") String issuer,
            @Value("${app.security.audience}") String audience
    ) {
        FilterRegistrationBean<VerifiedJwtPrincipalFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new VerifiedJwtPrincipalFilter(jwtSecret, issuer, audience, new ObjectMapper()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 40);
        registration.addUrlPatterns("/upload/temp", "/upload/temp-file");
        return registration;
    }
}
