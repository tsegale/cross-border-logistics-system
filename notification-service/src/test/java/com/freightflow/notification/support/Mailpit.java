package com.freightflow.notification.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Reads what reached the SMTP server through Mailpit's HTTP API (https://mailpit.axllent.org/docs/api-v1/). */
public final class Mailpit {

    public static final String IMAGE = "axllent/mailpit:v1.34.0";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One received email: who it went to, its subject and both bodies. */
    public record Message(String id, List<String> to, String subject, String text, String html,
                          String messageId) {
    }

    private Mailpit() {
    }

    /** Every message to {@code recipient}, newest first. */
    public static List<Message> to(String recipient) {
        JsonNode found = get("/api/v1/search?query=" + URLEncoder.encode("to:\"" + recipient + "\"",
                StandardCharsets.UTF_8) + "&limit=50");
        List<Message> messages = new ArrayList<>();
        for (JsonNode summary : found.path("messages")) {
            JsonNode full = get("/api/v1/message/" + summary.path("ID").asString());
            List<String> to = new ArrayList<>();
            full.path("To").forEach(address -> to.add(address.path("Address").asString()));
            messages.add(new Message(summary.path("ID").asString(), to, full.path("Subject").asString(),
                    full.path("Text").asString(), full.path("HTML").asString(), full.path("MessageID").asString()));
        }
        return messages;
    }

    private static JsonNode get(String path) {
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(SharedContainers.mailpitApi()
                    + path)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Mailpit " + path + ": HTTP " + response.statusCode());
            }
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Mailpit " + path + " unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
