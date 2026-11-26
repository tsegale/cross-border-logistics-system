package com.freightflow.logistics.support;

import com.github.dockerjava.api.command.InspectContainerResponse;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The broker exactly as compose runs it (ADR 0022): {@code infra/kafka/start.sh} with SCRAM and the authorizer, then
 * {@code infra/kafka/init.sh} for the service users and the ACLs in {@code infra/kafka/acls.conf}. Every service user
 * gets a random password for this JVM, and the broker a certificate from this JVM's test CA ({@link TestCertificates})
 * in the agent's layout, so every listener is SASL_SSL as in compose (ADR 0039).
 *
 * <p>Tests connect from the host, so the broker also advertises an external listener on the mapped port. That port
 * is only known once the container exists, so the start command waits for the address, as Testcontainers' own
 * {@code KafkaContainer} does.
 */
public final class KafkaBroker extends GenericContainer<KafkaBroker> {

    public static final String IMAGE = "apache/kafka:4.3.1";
    private static final int EXTERNAL_PORT = 9094;
    private static final Path INFRA = Path.of("..", "infra", "kafka").toAbsolutePath().normalize();
    // monitoring: the collector's describe-only user (ADR 0031), so its ACLs are tested too.
    private static final String[] USERS = {"admin", "banking", "logistics", "notification", "monitoring"};

    private final Map<String, String> passwords = new LinkedHashMap<>();

    public KafkaBroker() {
        super(DockerImageName.parse(IMAGE));
        SecureRandom random = new SecureRandom();
        for (String user : USERS) {
            byte[] bytes = new byte[18];
            random.nextBytes(bytes);
            String password = HexFormat.of().formatHex(bytes);
            passwords.put(user, password);
            withCopyToContainer(Transferable.of(password), "/run/secrets/kafka_" + user + "_password");
        }
        withCopyFileToContainer(MountableFile.forHostPath(INFRA), "/etc/freightflow/kafka");
        TestCertificates.Identity kafka = TestCertificates.issue("kafka");
        withCopyToContainer(Transferable.of(kafka.certificatePem(), 0644), "/tls/cert.pem");
        withCopyToContainer(Transferable.of(kafka.privateKeyPem(), 0644), "/tls/key.pem");
        withCopyToContainer(Transferable.of(TestCertificates.caPem(), 0644), "/tls/ca.pem");
        withEnv("CLUSTER_ID", "5L6g3nShT-eMCtK--X86sw");
        withEnv("KAFKA_ADVERTISED_HOST", "127.0.0.1");
        withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx384m");
        withExposedPorts(EXTERNAL_PORT);
        withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("bash"));
        withCommand("-c", "while [ ! -f /tmp/external ]; do sleep 0.1; done; "
                + "export KAFKA_EXTERNAL_ADVERTISED=\"$(cat /tmp/external)\"; exec bash /etc/freightflow/kafka/start.sh");
        waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1).withStartupTimeout(Duration.ofMinutes(2)));
    }

    @Override
    protected void containerIsStarting(InspectContainerResponse containerInfo) {
        copyFileToContainer(Transferable.of(host() + ":" + getMappedPort(EXTERNAL_PORT)), "/tmp/external");
    }

    @Override
    protected void containerIsStarted(InspectContainerResponse containerInfo) {
        try {
            Container.ExecResult init = execInContainer("bash", "-c",
                    "KAFKA_BOOTSTRAP=127.0.0.1:29092 bash /etc/freightflow/kafka/init.sh");
            if (init.getExitCode() != 0) {
                throw new IllegalStateException("kafka-init failed: " + init.getStdout() + init.getStderr());
            }
        } catch (IOException e) {
            throw new IllegalStateException("kafka-init could not run", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("kafka-init interrupted", e);
        }
    }

    public String bootstrapServers() {
        return host() + ":" + getMappedPort(EXTERNAL_PORT);
    }

    public String password(String user) {
        return passwords.get(user);
    }

    /** What any Kafka client needs to log in as {@code user}. */
    public Map<String, Object> clientConfig(String user, String password) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
        config.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PEM");
        config.put(SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG, TestCertificates.caPem());
        config.put(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-512");
        config.put(SaslConfigs.SASL_JAAS_CONFIG, "org.apache.kafka.common.security.scram.ScramLoginModule required "
                + "username=\"" + user + "\" password=\"" + password + "\";");
        return config;
    }

    public Map<String, Object> clientConfig(String user) {
        return clientConfig(user, password(user));
    }

    /** "localhost" resolves to ::1 first on Windows; the broker listens on IPv4. */
    private String host() {
        return "localhost".equals(getHost()) ? "127.0.0.1" : getHost();
    }
}
