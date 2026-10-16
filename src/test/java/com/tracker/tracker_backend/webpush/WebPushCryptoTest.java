package com.tracker.tracker_backend.webpush;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.util.Arrays;
import java.util.Base64;

import static com.tracker.tracker_backend.webpush.WebPushCrypto.b64url;
import static com.tracker.tracker_backend.webpush.WebPushCrypto.b64urlDecode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebPushCryptoTest {

    // RFC 8291 Section 5 / Appendix A
    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    private static final String AS_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    // The RFC prints the 86-octet header and the ciphertext as separate base64url strings
    private static final String EXPECTED_HEADER =
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
          + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String EXPECTED_CIPHERTEXT =
            "8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ";

    @Test
    void matchesRfc8291Example() {
        KeyPair as = new KeyPair(
                WebPushCrypto.decodePublicKey(b64urlDecode(AS_PUBLIC)),
                WebPushCrypto.decodePrivateKey(b64urlDecode(AS_PRIVATE)));

        byte[] out = WebPushCrypto.encrypt(PLAINTEXT.getBytes(StandardCharsets.UTF_8),
                b64urlDecode(UA_PUBLIC), b64urlDecode(AUTH), as, b64urlDecode(SALT));

        assertThat(Arrays.copyOfRange(out, 0, 86)).isEqualTo(b64urlDecode(EXPECTED_HEADER));
        assertThat(Arrays.copyOfRange(out, 86, out.length)).isEqualTo(b64urlDecode(EXPECTED_CIPHERTEXT));
    }

    @Test
    void randomEncryptionDecryptsWithUserAgentKey() throws Exception {
        byte[] payload = "{\"title\":\"✈ AIC101 is overhead\"}".getBytes(StandardCharsets.UTF_8);
        KeyPair ua = WebPushCrypto.generateKeyPair();
        byte[] uaPublic = WebPushCrypto.encodePublicKey((java.security.interfaces.ECPublicKey) ua.getPublic());
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);

        byte[] out = WebPushCrypto.encrypt(payload, uaPublic, auth);

        assertThat(decrypt(out, (ECPrivateKey) ua.getPrivate(), uaPublic, auth)).isEqualTo(payload);
    }

    @Test
    void rejectsOversizedPayload() {
        KeyPair ua = WebPushCrypto.generateKeyPair();
        byte[] uaPublic = WebPushCrypto.encodePublicKey((java.security.interfaces.ECPublicKey) ua.getPublic());
        assertThatThrownBy(() -> WebPushCrypto.encrypt(new byte[4090], uaPublic, new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void vapidJwtIsValidEs256() throws Exception {
        KeyPair kp = WebPushCrypto.generateKeyPair();
        byte[] pub = WebPushCrypto.encodePublicKey((java.security.interfaces.ECPublicKey) kp.getPublic());
        byte[] priv = WebPushCrypto.encodePrivateKey((ECPrivateKey) kp.getPrivate());
        VapidSigner signer = new VapidSigner(pub, priv, "https://flights.example.com");

        String header = signer.authorizationHeader("https://fcm.googleapis.com/fcm/send/abc123");
        assertThat(header).startsWith("vapid t=").endsWith(", k=" + b64url(pub));

        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(claims).contains("\"aud\":\"https://fcm.googleapis.com\"").contains("\"sub\":\"https://flights.example.com\"");

        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(kp.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void vapidRejectsMismatchedKeys() {
        byte[] pub = WebPushCrypto.encodePublicKey(
                (java.security.interfaces.ECPublicKey) WebPushCrypto.generateKeyPair().getPublic());
        byte[] otherPriv = WebPushCrypto.encodePrivateKey((ECPrivateKey) WebPushCrypto.generateKeyPair().getPrivate());
        assertThatThrownBy(() -> new VapidSigner(pub, otherPriv, "https://x.example"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Receiver side of RFC 8291, implemented independently for the round-trip test. */
    private static byte[] decrypt(byte[] msg, ECPrivateKey uaPrivate, byte[] uaPublic, byte[] auth) throws Exception {
        byte[] salt = Arrays.copyOfRange(msg, 0, 16);
        int idLen = msg[20];
        byte[] asPublic = Arrays.copyOfRange(msg, 21, 21 + idLen);
        byte[] ct = Arrays.copyOfRange(msg, 21 + idLen, msg.length);

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(uaPrivate);
        ka.doPhase(WebPushCrypto.decodePublicKey(asPublic), true);
        byte[] ecdh = ka.generateSecret();

        byte[] prkKey = hmac(auth, ecdh);
        byte[] keyInfo = cat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic, new byte[]{1});
        byte[] ikm = hmac(prkKey, keyInfo);
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, cat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, cat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 12);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = c.doFinal(ct);
        assertThat(padded[padded.length - 1]).isEqualTo((byte) 2);
        return Arrays.copyOf(padded, padded.length - 1);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }

    private static byte[] cat(byte[]... parts) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }
}
