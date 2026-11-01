package com.applocker.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Credential storage.
 *
 * - The PIN/password itself is never stored. We store a PBKDF2-HmacSHA256 hash with a
 *   random salt, optionally wrapped by an AndroidKeyStore AES-GCM key (keystore-backed
 *   encryption of the stored verification material, so file theft alone is useless).
 * - Attempt rate limiting state lives here too (plaintext counters only, never secrets).
 * - Nothing in this class ever logs credential material.
 */
public final class CryptoStore {

    private static final String PREFS = "applocker_auth";
    private static final String KS_ALIAS = "applocker_cred_v1";
    private static final String GCM_TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    // Plain credential material keys
    private static final String K_SALT = "cred_salt";
    private static final String K_ITER = "cred_iter";
    private static final String K_HASH = "cred_hash";
    private static final String K_IV = "cred_iv";
    private static final String K_WRAPPED = "cred_wrapped";
    private static final String K_USE_KS = "cred_keystore";

    // Credential shape
    private static final String K_MODE = "mode";           // 0 = numeric PIN, 1 = alphanumeric password
    private static final String K_PIN_LEN = "pin_len";     // for auto-submit UX

    // Rate limiting
    private static final String K_FAILS = "fails";
    private static final String K_LOCKOUT_UNTIL = "lockout_until";

    public static final int MODE_PIN = 0;
    public static final int MODE_PASSWORD = 1;
    private static final int FAILS_BEFORE_LOCK = 5;
    private static final long BASE_LOCKOUT_MS = 30_000L;

    private final SharedPreferences sp;

    public CryptoStore(Context ctx) {
        this.sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isPinSet() {
        return sp.contains(K_HASH) || sp.contains(K_WRAPPED);
    }

    public int getMode() {
        return sp.getInt(K_MODE, MODE_PIN);
    }

    public int getPinLength() {
        return sp.getInt(K_PIN_LEN, 0);
    }

    /** Create or replace the stored credential. */
    public void setCredential(char[] credential, int mode) {
        int credLen = credential == null ? 0 : credential.length;
        byte[] salt = PinHasher.newSalt();
        int iter = PinHasher.DEFAULT_ITERATIONS;
        byte[] hash = PinHasher.hash(credential, salt, iter);
        java.util.Arrays.fill(credential, '\0');

        SharedPreferences.Editor e = sp.edit().clear()
                .putInt(K_ITER, iter)
                .putInt(K_MODE, mode)
                .putInt(K_PIN_LEN, mode == MODE_PIN ? credLen : 0);

        // store salt+hash, wrapped by Keystore when possible
        try {
            SecretKey key = getOrCreateKeystoreKey();
            Cipher c = Cipher.getInstance(GCM_TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(pack(salt, hash));
            e.putBoolean(K_USE_KS, true)
                    .putString(K_IV, b64(iv))
                    .putString(K_WRAPPED, b64(ct));
            e.remove(K_SALT).remove(K_HASH);
        } catch (Throwable t) {
            // Fallback: store PBKDF2 material directly (still never plaintext credentials).
            e.putBoolean(K_USE_KS, false)
                    .putString(K_SALT, b64(salt))
                    .putString(K_HASH, PinHasher.toHex(hash))
                    .remove(K_IV).remove(K_WRAPPED);
        }
        e.apply();
    }

    /** Set the expected numeric PIN length (UX auto-submit) after setCredential. */
    public void setPinLength(int len) {
        sp.edit().putInt(K_PIN_LEN, len).apply();
    }

    /** Verify a candidate credential. Rate-limiting enforced by {@link #lockedForMs()}. */
    public boolean verify(char[] candidate) {
        int iter = sp.getInt(K_ITER, PinHasher.DEFAULT_ITERATIONS);
        byte[] salt;
        String storedHex;

        if (sp.getBoolean(K_USE_KS, false)) {
            try {
                byte[] plain = decrypt(b64decode(sp.getString(K_IV, null)), b64decode(sp.getString(K_WRAPPED, null)));
                byte[][] sh = unpack(plain);
                salt = sh[0];
                storedHex = PinHasher.toHex(sh[1]);
            } catch (Throwable t) {
                java.util.Arrays.fill(candidate, '\0');
                return false;
            }
        } else {
            salt = b64decode(sp.getString(K_SALT, null));
            storedHex = sp.getString(K_HASH, null);
        }

        boolean ok = PinHasher.verify(candidate, salt, iter, storedHex);
        java.util.Arrays.fill(candidate, '\0');
        if (ok) {
            sp.edit().remove(K_FAILS).remove(K_LOCKOUT_UNTIL).apply();
        } else {
            registerFailure();
        }
        return ok;
    }

    /** Remaining lockout in ms (0 = not locked). */
    public long lockedForMs() {
        long until = sp.getLong(K_LOCKOUT_UNTIL, 0L);
        long now = System.currentTimeMillis();
        return until > now ? (until - now) : 0L;
    }

    private void registerFailure() {
        int fails = sp.getInt(K_FAILS, 0) + 1;
        long until = 0L;
        if (fails >= FAILS_BEFORE_LOCK) {
            long extra = BASE_LOCKOUT_MS * (1L << Math.min(fails - FAILS_BEFORE_LOCK, 5));
            until = System.currentTimeMillis() + extra;
        }
        sp.edit().putInt(K_FAILS, fails).putLong(K_LOCKOUT_UNTIL, until).apply();
    }

    /** Clear credential + counters (used by change-PIN flow). */
    public void clear() {
        sp.edit().clear().apply();
    }

    // ---- keystore helpers ----

    private static SecretKey getOrCreateKeystoreKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KS_ALIAS)) {
            KeyStore.SecretKeyEntry e = (KeyStore.SecretKeyEntry) ks.getEntry(KS_ALIAS, null);
            if (e != null) return e.getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KS_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    private byte[] decrypt(byte[] iv, byte[] ct) throws Exception {
        Cipher c = Cipher.getInstance(GCM_TRANSFORM);
        c.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return c.doFinal(ct);
    }

    private static byte[] pack(byte[] salt, byte[] hash) {
        byte[] out = new byte[4 + salt.length + hash.length];
        out[0] = (byte) (salt.length >> 24); out[1] = (byte) (salt.length >> 16);
        out[2] = (byte) (salt.length >> 8); out[3] = (byte) salt.length;
        System.arraycopy(salt, 0, out, 4, salt.length);
        System.arraycopy(hash, 0, out, 4 + salt.length, hash.length);
        return out;
    }

    private static byte[][] unpack(byte[] data) {
        int saltLen = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        byte[] salt = new byte[saltLen];
        byte[] hash = new byte[data.length - 4 - saltLen];
        System.arraycopy(data, 4, salt, 0, saltLen);
        System.arraycopy(data, 4 + saltLen, hash, 0, hash.length);
        return new byte[][]{salt, hash};
    }

    private static String b64(byte[] d) { return Base64.encodeToString(d, Base64.NO_WRAP); }
    private static byte[] b64decode(String s) { return s == null ? null : Base64.decode(s, Base64.NO_WRAP); }

    /** Length of a numeric pin string without materializing secrets beyond validation. */
    public static int pinLengthOf(String pin) { return pin == null ? 0 : pin.length(); }
}
