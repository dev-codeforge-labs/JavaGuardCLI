package com.devmanchego.javaguard.change;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers; lower-case hex, as {@link com.devmanchego.javaguard.model.Fingerprint} requires. */
public final class Hashing {

    private Hashing() {
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** First {@code hexChars} characters of the SHA-256 of {@code text}. */
    public static String shortSha256(String text, int hexChars) {
        return sha256(text).substring(0, hexChars);
    }
}
