package com.freightflow.logistics.dcsa;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/** DCSA's Notification-Signature: sha256=hex(HMAC-SHA256(Base64-decoded secret, body bytes)) (ADR 0037). */
class DcsaSignatureTest {

    // The example secret of DCSA's EVENT_DOMAIN 1.0.4 schema "secret".
    private static final String SECRET =
            "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDM2NTc4NjIzODk3NDY5MDgyNzM0OTg3MTIzNzg2NA==";
    private static final byte[] BODY = """
            [{"eventID":"3cecb101-7a1a-43a4-9d62-e88a131651e2","eventType":"EQUIPMENT"}]
            """.getBytes(StandardCharsets.UTF_8);

    @Test
    void theSignatureIsHmacSha256OfTheBodyWithTheDecodedSecret() throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(SECRET), "HmacSHA256"));
        String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(BODY));

        assertThat(DcsaSignature.sign(SECRET, BODY)).isEqualTo(expected);
        assertThat(DcsaSignature.verify(SECRET, BODY, expected)).isTrue();
        assertThat(DcsaSignature.verify(SECRET, BODY, "  " + expected.toUpperCase().replace("SHA256", "sha256") + " "))
                .as("hex case and surrounding spaces do not matter").isTrue();
    }

    @Test
    void anyChangeToTheBodyOrTheKeyFails() {
        String signature = DcsaSignature.sign(SECRET, BODY);
        byte[] tampered = new String(BODY, StandardCharsets.UTF_8).replace("EQUIPMENT", "TRANSPORT")
                .getBytes(StandardCharsets.UTF_8);
        byte[] reformatted = new String(BODY, StandardCharsets.UTF_8).replace(",", ", ").getBytes(StandardCharsets.UTF_8);

        assertThat(DcsaSignature.verify(SECRET, tampered, signature)).isFalse();
        assertThat(DcsaSignature.verify(SECRET, reformatted, signature)).as("whitespace is signed too").isFalse();
        assertThat(DcsaSignature.verify(Base64.getEncoder().encodeToString("another secret".getBytes()), BODY,
                signature)).isFalse();
    }

    @Test
    void malformedHeadersFail() {
        String hex = DcsaSignature.sign(SECRET, BODY).substring("sha256=".length());
        assertThat(DcsaSignature.verify(SECRET, BODY, null)).isFalse();
        assertThat(DcsaSignature.verify(SECRET, BODY, hex)).as("no type").isFalse();
        assertThat(DcsaSignature.verify(SECRET, BODY, "sha512=" + hex)).as("unsupported type").isFalse();
        assertThat(DcsaSignature.verify(SECRET, BODY, "sha256=not-hex")).isFalse();
    }
}
