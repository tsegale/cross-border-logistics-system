package com.freightflow.logistics.events;

import com.freightflow.logistics.support.KafkaBroker;
import com.freightflow.logistics.support.TestCertificates;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.images.builder.Transferable;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The broker's certificate is replaced under traffic without a restart (ADR 0039): {@code infra/kafka/start.sh}
 * notices the agent's new files and reloads its listeners, a producer sending all along sees no failed send, and a new
 * TLS connection gets the new certificate. Its own broker, checking every second, since this one is changed.
 */
class KafkaCertificateRotationIntegrationTest {

    private static final String TOPIC = "freightflow.rotation-test.v1";
    private static final String RELOADED = "kafka tls: certificate renewed, listeners reloaded";
    private static final KafkaBroker BROKER = new KafkaBroker().withEnv("TLS_CHECK_SECONDS", "1");

    @BeforeAll
    static void start() throws Exception {
        BROKER.start();
        try (Admin admin = Admin.create(BROKER.clientConfig("admin"))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
    }

    @AfterAll
    static void stop() {
        BROKER.stop();
    }

    @Test
    void aRenewedCertificateIsServedWhileProducersKeepSending() throws Exception {
        X509Certificate before = served();
        Map<String, Object> config = new java.util.HashMap<>(BROKER.clientConfig("admin"));
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        AtomicBoolean sending = new AtomicBoolean(true);
        AtomicInteger acknowledged = new AtomicInteger();
        ExecutorService producerThread = Executors.newSingleThreadExecutor();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config, new StringSerializer(),
                new StringSerializer())) {
            Future<Integer> traffic = producerThread.submit(() -> {
                int sent = 0;
                while (sending.get()) {
                    // get(): a send that fails during the reload fails the test.
                    producer.send(new ProducerRecord<>(TOPIC, "key", "record " + sent)).get(15, TimeUnit.SECONDS);
                    acknowledged.incrementAndGet();
                    sent++;
                    Thread.sleep(20);
                }
                return sent;
            });

            // What the agent does on a renewal: a new key and certificate for the same name, key first.
            TestCertificates.Identity renewed = TestCertificates.issue("kafka");
            BROKER.copyFileToContainer(Transferable.of(renewed.privateKeyPem(), 0644), "/tls/key.pem");
            BROKER.copyFileToContainer(Transferable.of(renewed.certificatePem(), 0644), "/tls/cert.pem");
            X509Certificate expected = parse(renewed.certificatePem());

            // Both steps of the reload (start.sh, watch_certificate) are done once the broker says so.
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (!BROKER.getLogs().contains(RELOADED) && System.nanoTime() < deadline) {
                Thread.sleep(500);
            }
            assertThat(BROKER.getLogs()).contains(RELOADED);
            X509Certificate now = served();
            int duringRotation = acknowledged.get();
            Thread.sleep(1_000);
            sending.set(false);
            int sent = traffic.get(30, TimeUnit.SECONDS);

            assertThat(now).as("a new connection gets the renewed certificate").isEqualTo(expected);
            assertThat(now.getSerialNumber()).isNotEqualTo(before.getSerialNumber());
            assertThat(sent).as("every send of the producer that was connected all along was acknowledged")
                    .isEqualTo(acknowledged.get()).isGreaterThan(duringRotation);
        } finally {
            producerThread.shutdownNow();
        }
    }

    /** The certificate the broker's TLS listener presents to a new connection, verified against the test CA. */
    private static X509Certificate served() throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("ca", parse(TestCertificates.caPem()));
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        String[] address = BROKER.bootstrapServers().split(":");
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory()
                .createSocket(address[0], Integer.parseInt(address[1]))) {
            socket.startHandshake();
            Certificate[] chain = socket.getSession().getPeerCertificates();
            return (X509Certificate) chain[0];
        }
    }

    private static X509Certificate parse(String pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    }
}
