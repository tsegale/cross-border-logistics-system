package com.freightflow.banking.tls;

import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This service's identity and trust on internal TLS (ADR 0039, ADR 0040): the {@code internal} SSL bundle, the agent's
 * certificate and key with the internal CA's root, which Spring Boot reloads when the agent renews them.
 *
 * <p>A client builds its {@link SSLContext} once (the JDK's HttpClient does), so it would keep the key it started
 * with and present an expired certificate a day later. The context here asks the bundle's current managers on every
 * handshake, so a client built from it follows each renewal without being rebuilt. The alias is the same across
 * reloads (the bundle's PEM key store has one entry), so a renewal between the steps of one handshake can at worst
 * fail that handshake, and the connection is opened again.
 */
public final class InternalTls {

    public static final String BUNDLE = "internal";
    private static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private final AtomicReference<X509ExtendedKeyManager> keys = new AtomicReference<>();
    private final AtomicReference<X509ExtendedTrustManager> trust = new AtomicReference<>();
    private final SSLContext context;

    public InternalTls(SslBundles bundles) {
        use(bundles.getBundle(BUNDLE));
        bundles.addBundleUpdateHandler(BUNDLE, this::use);
        try {
            context = SSLContext.getInstance("TLS");
            context.init(new KeyManager[] {new CurrentKeyManager()}, new TrustManager[] {new CurrentTrustManager()},
                    null);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not create the internal TLS context", e);
        }
    }

    /** An SSLContext that presents the current certificate and trusts only the internal root. */
    public SSLContext sslContext() {
        return context;
    }

    /** TLS 1.3 and 1.2 only; the server's name is checked against its certificate. */
    public SSLParameters sslParameters() {
        SSLParameters parameters = context.getDefaultSSLParameters();
        parameters.setProtocols(PROTOCOLS);
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        return parameters;
    }

    private void use(SslBundle bundle) {
        keys.set(Arrays.stream(bundle.getManagers().getKeyManagers())
                .filter(X509ExtendedKeyManager.class::isInstance).map(X509ExtendedKeyManager.class::cast)
                .findFirst().orElseThrow(() -> new IllegalStateException("the internal bundle has no key")));
        trust.set(Arrays.stream(bundle.getManagers().getTrustManagers())
                .filter(X509ExtendedTrustManager.class::isInstance).map(X509ExtendedTrustManager.class::cast)
                .findFirst().orElseThrow(() -> new IllegalStateException("the internal bundle has no root")));
    }

    private final class CurrentKeyManager extends X509ExtendedKeyManager {
        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return keys.get().getClientAliases(keyType, issuers);
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return keys.get().chooseClientAlias(keyType, issuers, socket);
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return keys.get().chooseEngineClientAlias(keyType, issuers, engine);
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return keys.get().getServerAliases(keyType, issuers);
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return keys.get().chooseServerAlias(keyType, issuers, socket);
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return keys.get().chooseEngineServerAlias(keyType, issuers, engine);
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return keys.get().getCertificateChain(alias);
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return keys.get().getPrivateKey(alias);
        }
    }

    private final class CurrentTrustManager extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            trust.get().checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            trust.get().checkServerTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            trust.get().checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            trust.get().checkServerTrusted(chain, authType, engine);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            trust.get().checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            trust.get().checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return trust.get().getAcceptedIssuers();
        }
    }
}
