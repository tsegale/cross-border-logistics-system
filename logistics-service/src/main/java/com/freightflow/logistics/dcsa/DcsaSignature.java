package com.freightflow.logistics.dcsa;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * DCSA's notification signature (ADR 0037): {@code Notification-Signature: sha256=<hex>}, HMAC-SHA256 over the entire
 * request body as sent, keyed with the subscription's shared secret, Base64-decoded. EVENT_DOMAIN 1.0.4, parameter
 * notificationSignature; the algorithm as DCSA's reference implementation computes it (DCSA-Event-Core,
 * MessageSignatureHandler, HMAC_SHA256). Neither headers nor the URL are signed.
 */
public final class DcsaSignature {

    public static final String HEADER = "Notification-Signature";
    private static final String ALGORITHM = "HmacSHA256";

    private DcsaSignature() {
    }

    public static String sign(String base64Secret, byte[] body) {
        return "sha256=" + HexFormat.of().formatHex(hmac(base64Secret, body));
    }

    /** Whether the header is a sha256 signature of exactly these bytes with this secret; constant time. */
    public static boolean verify(String base64Secret, byte[] body, String header) {
        if (header == null) {
            return false;
        }
        String value = header.trim();
        int equals = value.indexOf('=');
        if (equals < 0 || !value.substring(0, equals).toLowerCase(Locale.ROOT).equals("sha256")) {
            return false;
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(value.substring(equals + 1).toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException notHex) {
            return false;
        }
        return MessageDigest.isEqual(hmac(base64Secret, body), given);
    }

    private static byte[] hmac(String base64Secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(base64Secret.trim()), ALGORITHM));
            return mac.doFinal(body);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }
}
