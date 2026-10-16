package com.tracker.tracker_backend.service;

import com.tracker.tracker_backend.dto.PushSubscriptionRequest;
import com.tracker.tracker_backend.entity.PushSubscription;
import com.tracker.tracker_backend.repository.PushSubscriptionRepository;
import com.tracker.tracker_backend.webpush.WebPushCrypto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Stores browser push subscriptions.
 *
 * The server POSTs to whatever endpoint is stored here, so endpoints are restricted
 * to HTTPS URLs on the known browser push services (prevents SSRF).
 */
@Service
@RequiredArgsConstructor
public class PushSubscriptionService {

    private static final int MAX_DEVICES_PER_USER = 10;

    // Chrome/Edge/Android (FCM), Firefox (Mozilla autopush), Safari/iOS (APNs web push), legacy Edge (WNS)
    private static final List<String> ALLOWED_HOST_SUFFIXES = List.of(
            ".googleapis.com", ".mozilla.com", ".push.apple.com", ".notify.windows.com"
    );

    private final PushSubscriptionRepository repository;

    @Transactional
    public void subscribe(UUID userId, PushSubscriptionRequest req, String userAgent) {
        validateEndpoint(req.endpoint());
        validateKeys(req.keys().p256dh(), req.keys().auth());

        // Same browser re-subscribing (or switching accounts): update in place
        PushSubscription sub = repository.findByEndpoint(req.endpoint()).orElse(null);
        if (sub == null) {
            if (repository.countByUserId(userId) >= MAX_DEVICES_PER_USER) {
                throw new IllegalArgumentException("Too many devices registered (max " + MAX_DEVICES_PER_USER + ")");
            }
            sub = new PushSubscription();
            sub.setEndpoint(req.endpoint());
        }
        sub.setUserId(userId);
        sub.setP256dh(req.keys().p256dh());
        sub.setAuth(req.keys().auth());
        sub.setUserAgent(userAgent != null && userAgent.length() > 255 ? userAgent.substring(0, 255) : userAgent);
        repository.save(sub);
    }

    @Transactional
    public void unsubscribe(UUID userId, String endpoint) {
        repository.deleteByUserIdAndEndpoint(userId, endpoint);
    }

    public long countDevices(UUID userId) {
        return repository.countByUserId(userId);
    }

    static void validateEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid push endpoint");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = ALLOWED_HOST_SUFFIXES.stream().anyMatch(host::endsWith);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !allowed || uri.getPort() != -1) {
            throw new IllegalArgumentException("Unsupported push service endpoint");
        }
    }

    private static void validateKeys(String p256dh, String auth) {
        try {
            WebPushCrypto.decodePublicKey(WebPushCrypto.b64urlDecode(p256dh));
            if (WebPushCrypto.b64urlDecode(auth).length != 16) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid push subscription keys");
        }
    }
}
