package com.tracker.tracker_backend.controller;

import com.tracker.tracker_backend.dto.*;
import com.tracker.tracker_backend.security.DeviceAuthInterceptor;
import com.tracker.tracker_backend.security.RateLimiter;
import com.tracker.tracker_backend.service.NotificationService;
import com.tracker.tracker_backend.service.PushSubscriptionService;
import com.tracker.tracker_backend.service.UserService;
import com.tracker.tracker_backend.webpush.WebPushService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API for the browser app. Everything under /me requires the device token
 * issued by /register (see DeviceAuthInterceptor).
 */
@RestController
@RequestMapping("/api/v1/web")
@RequiredArgsConstructor
public class WebUserController {

    private static final String USER_ID = DeviceAuthInterceptor.USER_ID_ATTRIBUTE;

    private final UserService userService;
    private final PushSubscriptionService pushSubscriptionService;
    private final NotificationService notificationService;
    private final WebPushService webPushService;
    private final RateLimiter rateLimiter;

    @GetMapping("/vapid-public-key")
    public Map<String, String> vapidPublicKey() {
        if (!webPushService.isEnabled()) {
            throw new IllegalStateException("Web push is not configured");
        }
        return Map.of("publicKey", webPushService.publicKey());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public WebRegisterResponse register(@Valid @RequestBody WebRegisterRequest req, HttpServletRequest http) {
        rateLimiter.check("register", RateLimiter.clientIp(http), 10, Duration.ofHours(1));
        return userService.registerWebUser(req);
    }

    @GetMapping("/me")
    public MeResponse me(@RequestAttribute(USER_ID) UUID userId) {
        return userService.me(userId);
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe(@RequestAttribute(USER_ID) UUID userId) {
        userService.deleteUser(userId);
    }

    @PutMapping("/me/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateLocation(@RequestAttribute(USER_ID) UUID userId, @Valid @RequestBody WebLocationRequest req) {
        userService.replaceLocationFromPoint(userId, req.latitude(), req.longitude(), req.label());
    }

    @PatchMapping("/me/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setActive(@RequestAttribute(USER_ID) UUID userId, @RequestParam boolean active) {
        userService.setActive(userId, active);
    }

    @PostMapping("/me/push-subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscribe(@RequestAttribute(USER_ID) UUID userId,
                          @Valid @RequestBody PushSubscriptionRequest req,
                          @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        pushSubscriptionService.subscribe(userId, req, userAgent);
    }

    @DeleteMapping("/me/push-subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@RequestAttribute(USER_ID) UUID userId, @Valid @RequestBody PushUnsubscribeRequest req) {
        pushSubscriptionService.unsubscribe(userId, req.endpoint());
    }

    @PostMapping("/me/test-notification")
    public NotificationService.PushDelivery testNotification(@RequestAttribute(USER_ID) UUID userId) {
        rateLimiter.check("test-push", userId.toString(), 5, Duration.ofMinutes(10));
        return notificationService.sendTestPush(userId);
    }

    @GetMapping("/me/notifications")
    public List<NotificationView> notifications(@RequestAttribute(USER_ID) UUID userId,
                                                @RequestParam(defaultValue = "20") int limit) {
        return userService.recentNotifications(userId, Math.max(1, Math.min(limit, 100)));
    }
}
