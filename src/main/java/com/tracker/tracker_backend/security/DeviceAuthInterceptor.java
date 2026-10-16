package com.tracker.tracker_backend.security;

import com.tracker.tracker_backend.entity.User;
import com.tracker.tracker_backend.exception.UnauthorizedException;
import com.tracker.tracker_backend.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Authenticates web users by their device token ({@code Authorization: Bearer <token>})
 * and exposes the user id as the {@value #USER_ID_ATTRIBUTE} request attribute.
 */
@Component
@RequiredArgsConstructor
public class DeviceAuthInterceptor implements HandlerInterceptor {

    public static final String USER_ID_ATTRIBUTE = "authUserId";

    private final UserRepository userRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ") || header.length() <= 7) {
            throw new UnauthorizedException("Missing device token");
        }
        User user = userRepository.findByApiTokenHash(ApiTokens.hash(header.substring(7).strip()))
                .orElseThrow(() -> new UnauthorizedException("Invalid device token"));
        request.setAttribute(USER_ID_ATTRIBUTE, user.getId());
        return true;
    }
}
