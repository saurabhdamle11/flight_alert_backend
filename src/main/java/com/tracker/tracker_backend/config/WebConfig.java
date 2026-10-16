package com.tracker.tracker_backend.config;

import com.tracker.tracker_backend.security.AdminAuthInterceptor;
import com.tracker.tracker_backend.security.DeviceAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the browser app and authentication interceptors:
 *   /api/v1/web/me/**  → device token (web users)
 *   /api/v1/users/**   → admin token
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final DeviceAuthInterceptor deviceAuthInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;

    @Value("${app.cors.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v1/web/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(deviceAuthInterceptor).addPathPatterns("/api/v1/web/me", "/api/v1/web/me/**");
        registry.addInterceptor(adminAuthInterceptor).addPathPatterns("/api/v1/users", "/api/v1/users/**");
    }
}
