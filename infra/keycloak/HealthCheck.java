import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;

/**
 * Keycloak's container healthcheck (docker-compose.yml). Its management port takes HTTPS only since Phase 7, and the
 * image has no curl and no TLS-capable shell tool, so this asks {@code /health/ready} over HTTPS, verifying the server
 * against the internal CA's root (ADR 0039), and exits 0 only on 200 with status UP.
 *
 * <pre>java -cp /opt/freightflow HealthCheck https://localhost:9000/health/ready /opt/keycloak/tls/ca.pem</pre>
 */
public final class HealthCheck {

    private HealthCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: HealthCheck <url> <ca.pem>");
            System.exit(2);
        }
        KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType());
        roots.load(null, null);
        try (InputStream pem = Files.newInputStream(Path.of(args[1]))) {
            roots.setCertificateEntry("internal-ca", CertificateFactory.getInstance("X.509").generateCertificate(pem));
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(roots);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, trust.getTrustManagers(), null);
        HttpClient client = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).build();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(args[0])).timeout(Duration.ofSeconds(4)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        boolean up = response.statusCode() == 200 && response.body().replace(" ", "").contains("\"status\":\"UP\"");
        if (!up) {
            System.err.println("not ready: HTTP " + response.statusCode());
        }
        System.exit(up ? 0 : 1);
    }
}
