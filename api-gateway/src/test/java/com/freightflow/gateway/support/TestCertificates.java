package com.freightflow.gateway.support;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * A throwaway CA for this test JVM, standing in for the internal CA (ADR 0039), and the certificates it issues in the
 * agent's layout: {@code cert.pem}, {@code key.pem} (PKCS#8) and {@code ca.pem}. EC P-256, valid for a day, as the
 * agent's are.
 */
public final class TestCertificates {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final KeyPair CA_KEYS = keyPair();
    private static final X500Name CA_NAME = new X500Name("CN=FreightFlow Test CA");
    private static final X509Certificate CA = sign(CA_NAME, CA_KEYS, true, new GeneralName[0]);

    private TestCertificates() {
    }

    /** One identity's files, as the agent writes them. */
    public record Identity(String certificatePem, String privateKeyPem) {
    }

    /** The root every client trusts. */
    public static String caPem() {
        return pem("CERTIFICATE", encoded(CA));
    }

    /** A server and client certificate for {@code name}, with {@code name}, localhost and 127.0.0.1 as its names. */
    public static Identity issue(String name) {
        KeyPair keys = keyPair();
        X509Certificate certificate = sign(new X500Name("CN=" + name), keys, false, new GeneralName[] {
                new GeneralName(GeneralName.dNSName, name),
                new GeneralName(GeneralName.dNSName, "localhost"),
                new GeneralName(GeneralName.iPAddress, "127.0.0.1")});
        return new Identity(pem("CERTIFICATE", encoded(certificate)), pkcs8(keys.getPrivate()));
    }

    private static X509Certificate sign(X500Name subject, KeyPair keys, boolean ca, GeneralName[] names) {
        Instant now = Instant.now();
        try {
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(CA_NAME,
                    new BigInteger(64, RANDOM), Date.from(now.minus(Duration.ofMinutes(5))),
                    Date.from(now.plus(Duration.ofDays(1))), subject, keys.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
            if (ca) {
                builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            } else {
                builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
                builder.addExtension(Extension.extendedKeyUsage, false,
                        new ExtendedKeyUsage(new KeyPurposeId[] {KeyPurposeId.id_kp_serverAuth,
                                KeyPurposeId.id_kp_clientAuth}));
                builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
            }
            PrivateKey signer = ca ? keys.getPrivate() : CA_KEYS.getPrivate();
            return new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(signer)));
        } catch (CertIOException | OperatorCreationException | GeneralSecurityException e) {
            throw new IllegalStateException("could not create a test certificate", e);
        }
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no EC P-256 in this JVM", e);
        }
    }

    private static byte[] encoded(X509Certificate certificate) {
        try {
            return certificate.getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not encode a test certificate", e);
        }
    }

    /** The JCA encodes a private key as PKCS#8, the format the agent writes. */
    private static String pkcs8(PrivateKey key) {
        return pem("PRIVATE KEY", key.getEncoded());
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
