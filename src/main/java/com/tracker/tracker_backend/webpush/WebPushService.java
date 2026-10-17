package com.tracker.tracker_backend.webpush;

import com.tracker.tracker_backend.entity.PushSubscription;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Sends encrypted Web Push messages to browser push services (FCM, Mozilla, Apple, WNS).
 * Disabled (with a warning) when VAPID keys are not configured.
 */
@Slf4j
@Service
public class WebPushService {

    /**
     * SENT: accepted by the push service. GONE: subscription expired (404/410).
     * REJECTED: permanent 4xx — retrying won't help. FAILED: transient (429, 5xx, network).
     */
    public enum Outcome { SENT, GONE, REJECTED, FAILED }

    public record SendResult(Outcome outcome, int status, String messageId) {}

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${webpush.public-key:}")
    private String publicKey;

    @Value("${webpush.private-key:}")
    private String privateKey;

    @Value("${webpush.subject}")
    private String subject;

    @Value("${webpush.ttl-seconds:600}")
    private int ttlSeconds;

    private VapidSigner signer;

    @PostConstruct
    void init() {
        if (publicKey.isBlank() || privateKey.isBlank()) {
            log.warn("VAPID keys not configured — web push notifications are disabled");
            return;
        }
        signer = new VapidSigner(WebPushCrypto.b64urlDecode(publicKey), WebPushCrypto.b64urlDecode(privateKey), subject);
        log.info("Web push enabled (subject {})", subject);
    }

    public boolean isEnabled() {
        return signer != null;
    }

    /** Base64url VAPID public key the browser passes to pushManager.subscribe() as applicationServerKey. */
    public String publicKey() {
        return isEnabled() ? signer.publicKey() : null;
    }

    /**
     * No Topic header is sent: Apple rejects topics other push services accept
     * (e.g. containing '-') with 400 BadWebPushTopic. Duplicate alerts are already
     * prevented by the matcher's Redis dedup, and the notification tag collapses them on-device.
     */
    public SendResult send(PushSubscription sub, byte[] payload) {
        if (!isEnabled()) {
            throw new IllegalStateException("Web push is not configured");
        }
        byte[] body = WebPushCrypto.encrypt(payload,
                WebPushCrypto.b64urlDecode(sub.getP256dh()), WebPushCrypto.b64urlDecode(sub.getAuth()));

        SendResult result = post(sub, body);
        if (result.outcome() == Outcome.REJECTED && (result.status() == 401 || result.status() == 403)) {
            // Push service rejected our VAPID JWT (e.g. Apple ExpiredProviderToken): re-sign and retry once
            log.info("Push service {} rejected VAPID token (HTTP {}) — re-signing and retrying",
                    VapidSigner.audience(sub.getEndpoint()), result.status());
            signer.invalidate(sub.getEndpoint());
            result = post(sub, body);
        }
        return result;
    }

    private SendResult post(PushSubscription sub, byte[] body) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(sub.getEndpoint()))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", signer.authorizationHeader(sub.getEndpoint()))
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", String.valueOf(ttlSeconds))
                .header("Urgency", "high")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        try {
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            int status = res.statusCode();
            if (status >= 200 && status < 300) {
                return new SendResult(Outcome.SENT, status, res.headers().firstValue("Location").orElse(null));
            }
            if (status == 404 || status == 410) {
                return new SendResult(Outcome.GONE, status, null);
            }
            log.warn("Push service {} returned {}: {}", VapidSigner.audience(sub.getEndpoint()), status,
                    res.body().length() > 300 ? res.body().substring(0, 300) : res.body());
            boolean transientError = status == 429 || status >= 500;
            return new SendResult(transientError ? Outcome.FAILED : Outcome.REJECTED, status, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SendResult(Outcome.FAILED, 0, null);
        } catch (Exception e) {
            log.warn("Push to {} failed: {}", VapidSigner.audience(sub.getEndpoint()), e.getMessage());
            return new SendResult(Outcome.FAILED, 0, null);
        }
    }
}
