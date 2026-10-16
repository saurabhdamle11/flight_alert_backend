package com.tracker.tracker_backend.webpush;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.Arrays;
import java.util.Base64;

/**
 * Web Push message encryption (RFC 8291, "aes128gcm" content coding from RFC 8188)
 * and P-256 key helpers, using only JDK crypto.
 */
public final class WebPushCrypto {

    public static final int RECORD_SIZE = 4096;

    private static final byte[] KEY_INFO_PREFIX = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ECParameterSpec P256 = p256Params();

    private WebPushCrypto() {}

    // ── Encryption ────────────────────────────────────────────────────────────

    /** Encrypts a push payload for the given subscription keys with a fresh ephemeral key and salt. */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, uaPublic, authSecret, generateKeyPair(), salt);
    }

    /** Deterministic variant (fixed server key pair and salt) used by tests against the RFC 8291 example. */
    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair asKeys, byte[] salt) {
        // Single record: plaintext + 0x02 delimiter + 16-byte tag must fit in the record size
        if (plaintext.length + 1 + 16 > RECORD_SIZE) {
            throw new IllegalArgumentException("Push payload too large: " + plaintext.length + " bytes");
        }
        try {
            byte[] asPublic = encodePublicKey((ECPublicKey) asKeys.getPublic());

            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(asKeys.getPrivate());
            ka.doPhase(decodePublicKey(uaPublic), true);
            byte[] ecdhSecret = ka.generateSecret();

            // RFC 8291 §3.4: combine ECDH secret with the auth secret
            byte[] prkKey = hmac(authSecret, ecdhSecret);
            byte[] ikm = hmac(prkKey, concat(KEY_INFO_PREFIX, uaPublic, asPublic, new byte[]{1}));

            // RFC 8188 §2.2/2.3: derive content encryption key and nonce
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat(CEK_INFO, new byte[]{1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat(NONCE_INFO, new byte[]{1})), 12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[]{2}));

            // Header: salt(16) | record size(4) | key id length(1) | key id = as_public(65)
            ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
            header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
            return concat(header.array(), ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Web push encryption failed", e);
        }
    }

    // ── Keys ──────────────────────────────────────────────────────────────────

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return gen.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Uncompressed SEC1 point: 0x04 | X(32) | Y(32). */
    public static byte[] encodePublicKey(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(unsigned32(key.getW().getAffineX()), 0, out, 1, 32);
        System.arraycopy(unsigned32(key.getW().getAffineY()), 0, out, 33, 32);
        return out;
    }

    public static ECPublicKey decodePublicKey(byte[] uncompressed) {
        if (uncompressed.length != 65 || uncompressed[0] != 0x04) {
            throw new IllegalArgumentException("Expected a 65-byte uncompressed P-256 public key");
        }
        try {
            BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
            BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
            return (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid P-256 public key", e);
        }
    }

    /** Raw 32-byte private scalar d. */
    public static ECPrivateKey decodePrivateKey(byte[] d) {
        if (d.length != 32) {
            throw new IllegalArgumentException("Expected a 32-byte P-256 private key");
        }
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid P-256 private key", e);
        }
    }

    public static byte[] encodePrivateKey(ECPrivateKey key) {
        return unsigned32(key.getS());
    }

    public static byte[] b64urlDecode(String value) {
        return Base64.getUrlDecoder().decode(value.strip().replace('+', '-').replace('/', '_').replace("=", ""));
    }

    public static String b64url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    private static byte[] unsigned32(BigInteger n) {
        byte[] raw = n.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static ECParameterSpec p256Params() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
