package com.freightflow.notification.support;

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

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A throwaway CA for this test JVM, standing in for the internal CA (ADR 0039), and the certificates it issues in the
 * agent's layout: {@code cert.pem}, {@code key.pem} (PKCS#8) and {@code ca.pem}. EC P-256, valid for a day, as the
 * agent's are.
 */
public final class TestCertificates {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final KeyPair CA_KEYS = keyPair();
    private static final X500Name CA_NAME = new X500Name("CN=FreightFlow Test CA");
    private static final X509Certificate CA = sign(CA_NAME, CA_KEYS, true, new GeneralName[0], CA_NAME, CA_KEYS);
    /** A second CA nobody trusts: what a client from outside the stack would present. */
    private static final KeyPair OTHER_CA_KEYS = keyPair();
    private static final X500Name OTHER_CA_NAME = new X500Name("CN=Not The FreightFlow CA");
    private static final Map<String, Identity> ISSUED = new ConcurrentHashMap<>();

    private TestCertificates() {
    }

    /** One identity's files, as the agent writes them. */
    public record Identity(String certificatePem, String privateKeyPem) {
    }

    /** The root every client trusts. */
    public static String caPem() {
        return pem("CERTIFICATE", encoded(CA));
    }

    /** The one identity for {@code name} in this JVM: a context and its clients see the same certificate. */
    public static Identity of(String name) {
        return ISSUED.computeIfAbsent(name, TestCertificates::issue);
    }

    /** A server and client certificate for {@code name}, with {@code name}, localhost and 127.0.0.1 as its names. */
    public static Identity issue(String name) {
        return issue(name, CA_NAME, CA_KEYS);
    }

    /** The same name, from a CA nobody in the stack trusts. */
    public static Identity untrusted(String name) {
        return issue(name, OTHER_CA_NAME, OTHER_CA_KEYS);
    }

    /**
     * A client's TLS: trusts only the test CA and, with an identity, presents it (the services take mutual TLS,
     * ADR 0040); without one it presents no certificate.
     */
    public static SSLContext clientContext(Identity identity) {
        try {
            CertificateFactory x509 = CertificateFactory.getInstance("X.509");
            KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType());
            roots.load(null, null);
            roots.setCertificateEntry("test-ca", x509.generateCertificate(
                    new ByteArrayInputStream(caPem().getBytes(StandardCharsets.US_ASCII))));
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(roots);
            KeyManager[] keys = null;
            if (identity != null) {
                Certificate certificate = x509.generateCertificate(
                        new ByteArrayInputStream(identity.certificatePem().getBytes(StandardCharsets.US_ASCII)));
                String base64 = identity.privateKeyPem().replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
                PrivateKey key = KeyFactory.getInstance("EC")
                        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
                KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
                store.load(null, null);
                store.setKeyEntry("client", key, new char[0], new Certificate[] {certificate});
                KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                factory.init(store, new char[0]);
                keys = factory.getKeyManagers();
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys, trust.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new IllegalStateException("could not build a client TLS context", e);
        }
    }

    private static Identity issue(String name, X500Name issuer, KeyPair issuerKeys) {
        KeyPair keys = keyPair();
        X509Certificate certificate = sign(new X500Name("CN=" + name), keys, false, new GeneralName[] {
                new GeneralName(GeneralName.dNSName, name),
                new GeneralName(GeneralName.dNSName, "localhost"),
                new GeneralName(GeneralName.iPAddress, "127.0.0.1")}, issuer, issuerKeys);
        return new Identity(pem("CERTIFICATE", encoded(certificate)), pkcs8(keys.getPrivate()));
    }

    private static X509Certificate sign(X500Name subject, KeyPair keys, boolean ca, GeneralName[] names,
                                        X500Name issuer, KeyPair issuerKeys) {
        Instant now = Instant.now();
        try {
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuer,
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
            PrivateKey signer = issuerKeys.getPrivate();
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
