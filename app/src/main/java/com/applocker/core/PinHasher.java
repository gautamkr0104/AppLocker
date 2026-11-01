package com.applocker.core;

import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-HmacSHA256 credential hashing. Pure Java so it is unit-testable on the JVM.
 * Never logs or returns plaintext. Comparison is constant-time.
 */
public final class PinHasher {

    private PinHasher() {}

    public static final int DEFAULT_ITERATIONS = 120_000;
    public static final int KEY_BITS = 256;
    public static final int SALT_BYTES = 16;

    public static byte[] newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /** Derive PBKDF2-HmacSHA256 key bytes for the given credential. */
    public static byte[] hash(char[] credential, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(credential, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    public static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    public static byte[] fromHex(String hex) {
        int n = hex.length();
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) | Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    /** Constant-time equality for hashed credentials. */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) return false;
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    /** Verify a candidate credential against a stored hex hash. */
    public static boolean verify(char[] candidate, byte[] salt, int iterations, String storedHex) {
        if (candidate == null || storedHex == null || salt == null) return false;
        byte[] got = hash(candidate, salt, iterations);
        return constantTimeEquals(got, fromHex(storedHex));
    }
}
