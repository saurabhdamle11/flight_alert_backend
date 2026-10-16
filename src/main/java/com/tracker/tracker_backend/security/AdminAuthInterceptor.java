package com.tracker.tracker_backend.security;

import com.tracker.tracker_backend.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards the admin user-management API with a shared secret ({@code X-Admin-Token}).
 * When no admin token is configured the admin API is closed entirely.
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    @Value("${app.admin-token:}")
    private String adminToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String provided = request.getHeader("X-Admin-Token");
        if (adminToken.isBlank() || provided == null || !ApiTokens.constantTimeEquals(adminToken, provided)) {
            throw new UnauthorizedException("Admin token required");
        }
        return true;
    }
}
