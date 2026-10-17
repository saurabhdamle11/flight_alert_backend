package com.tracker.tracker_backend.webpush;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the VAPID (RFC 8292) Authorization header: an ES256 JWT scoped to the
 * push service origin, plus the application server public key.
 *
 * JWTs are short-lived: Apple's push service rejects tokens older than about an hour
 * with 403 ExpiredProviderToken (FCM/Mozilla accept up to 24 h). Each JWT is valid for
 * 1 h, cached per origin and re-signed once it is 30 min old.
 */
public class VapidSigner {

    private static final Duration JWT_LIFETIME = Duration.ofHours(1);
    private static final Duration REFRESH_BEFORE = Duration.ofMinutes(30);

    private final ECPrivateKey privateKey;
    private final String publicKeyB64;
    private final String subject;
    private final Map<String, CachedJwt> cache = new ConcurrentHashMap<>();

    private record CachedJwt(String jwt, Instant expiresAt) {}

    public VapidSigner(byte[] publicKey, byte[] privateKey, String subject) {
        this.privateKey = WebPushCrypto.decodePrivateKey(privateKey);
        this.publicKeyB64 = WebPushCrypto.b64url(publicKey);
        this.subject = subject;
        verifyKeyPair(WebPushCrypto.decodePublicKey(publicKey));
    }

    public String publicKey() {
        return publicKeyB64;
    }

    public String authorizationHeader(String endpoint) {
        return "vapid t=" + jwtFor(audience(endpoint)) + ", k=" + publicKeyB64;
    }

    /** Drops the cached JWT for this endpoint's push service so the next call re-signs. */
    public void invalidate(String endpoint) {
        cache.remove(audience(endpoint));
    }

    String jwtFor(String audience) {
        Instant now = Instant.now();
        CachedJwt cached = cache.get(audience);
        if (cached != null && now.plus(REFRESH_BEFORE).isBefore(cached.expiresAt())) {
            return cached.jwt();
        }
        Instant exp = now.plus(JWT_LIFETIME);
        String header = WebPushCrypto.b64url("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = WebPushCrypto.b64url(String.format(
                "{\"aud\":\"%s\",\"exp\":%d,\"sub\":\"%s\"}", audience, exp.getEpochSecond(), subject
        ).getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        String jwt = signingInput + "." + WebPushCrypto.b64url(sign(signingInput.getBytes(StandardCharsets.US_ASCII)));
        cache.put(audience, new CachedJwt(jwt, exp));
        return jwt;
    }

    static String audience(String endpoint) {
        URI uri = URI.create(endpoint);
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
    }

    private byte[] sign(byte[] data) {
        try {
            // JWS ES256 wants the raw 64-byte R||S signature, not DER
            Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
            sig.initSign(privateKey);
            sig.update(data);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("VAPID signing failed", e);
        }
    }

    /** Fail fast at startup if the configured public and private keys don't belong together. */
    private void verifyKeyPair(ECPublicKey publicKey) {
        try {
            byte[] probe = "vapid-key-check".getBytes(StandardCharsets.US_ASCII);
            Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(publicKey);
            verifier.update(probe);
            if (!verifier.verify(sign(probe))) {
                throw new IllegalArgumentException("VAPID public key does not match the private key");
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
