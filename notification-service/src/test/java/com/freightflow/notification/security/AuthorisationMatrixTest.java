package com.freightflow.notification.security;

import com.freightflow.notification.support.IntegrationTest;
import com.freightflow.notification.support.TestCallers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Every notification-service endpoint x every role, against the policy table of ADR 0018 as ADR 0027 extends it.
 * Settings are a customer's own organisation's, never another's: the organisation comes from the token. A customer
 * sees only its own notifications, whatever it asks for.
 */
class AuthorisationMatrixTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    enum Who {
        CUSTOMER(TestCallers.as(TestCallers.CUSTOMER_NAMIB)),
        OTHER_CUSTOMER(TestCallers.as(TestCallers.CUSTOMER_HANSEATIC)),
        OPERATOR(TestCallers.as(TestCallers.OPERATOR)),
        AUDITOR(TestCallers.as(TestCallers.AUDITOR)),
        CARRIER(TestCallers.as(TestCallers.CARRIER)),
        ANONYMOUS(anonymous());

        final RequestPostProcessor token;

        Who(RequestPostProcessor token) {
            this.token = token;
        }
    }

    /** Columns: customer, another customer, operator, auditor, carrier, anonymous. */
    static Stream<Arguments> policy() {
        Map<String, int[]> table = new LinkedHashMap<>();
        //                                         CUS  OCUS OP   AUD  CAR  ANON
        table.put("GET settings",           new int[] {200, 200, 403, 403, 403, 401});
        table.put("PUT settings",           new int[] {200, 200, 403, 403, 403, 401});
        table.put("GET notifications",      new int[] {200, 200, 200, 200, 403, 401});
        table.put("GET unknown notifications", new int[] {200, 200, 200, 200, 403, 401});
        table.put("POST notification resend", new int[] {403, 403, 200, 403, 403, 401});
        table.put("GET dead letters",       new int[] {403, 403, 200, 200, 403, 401});
        table.put("GET dead letter",        new int[] {403, 403, 200, 200, 403, 401});
        table.put("POST dead letter redrive", new int[] {403, 403, 409, 403, 403, 401});
        table.put("GET anything else",      new int[] {403, 403, 403, 403, 403, 401});
        List<Arguments> cases = new ArrayList<>();
        table.forEach((endpoint, expected) -> {
            for (Who who : Who.values()) {
                cases.add(Arguments.of(endpoint, who, expected[who.ordinal()]));
            }
        });
        return cases.stream();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID deadLetter;

    private UUID unknownEmail;

    /** A poison dead letter: redriving it fails again (409), which is what an operator is allowed to find out. */
    @BeforeEach
    void deadLetter() {
        deadLetter = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO dead_letters (id, topic, record_partition, record_offset, original_topic, record_value,
                                          error, received_at, status)
                VALUES (?, 'freightflow.hold.v1.notification-service-dlt', 0, ?, 'freightflow.hold.v1', '{not json',
                        'poison', ?, 'NEW')
                """, deadLetter, Math.abs(deadLetter.getLeastSignificantBits()), Timestamp.from(Instant.now()));
        unknownEmail = notification(TestCallers.NAMIB, "UNKNOWN", 1);
    }

    @ParameterizedTest(name = "{0} as {1} -> {2}")
    @MethodSource("policy")
    void everyEndpointAndRoleMatchesThePolicy(String endpoint, Who who, int expected) throws Exception {
        MvcResult result = mockMvc.perform(request(endpoint).with(who.token)).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("%s as %s: %s", endpoint, who, result.getResponse().getContentAsString())
                .isEqualTo(expected);
    }

    private MockHttpServletRequestBuilder request(String endpoint) {
        return switch (endpoint) {
            case "GET settings" -> get("/api/v1/notification-settings");
            case "PUT settings" -> put("/api/v1/notification-settings").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"language\":\"de\",\"recipients\":[],\"kinds\":[\"shipment-booked\"]}");
            case "GET notifications" -> get("/api/v1/notifications");
            case "GET unknown notifications" -> get("/api/v1/notifications").param("status", "UNKNOWN");
            case "POST notification resend" -> post("/api/v1/notifications/{id}/resend", unknownEmail)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgeDuplicate\":true}");
            case "GET dead letters" -> get("/api/v1/dead-letters");
            case "GET dead letter" -> get("/api/v1/dead-letters/{id}", deadLetter);
            case "POST dead letter redrive" -> post("/api/v1/dead-letters/{id}/redrive", deadLetter);
            case "GET anything else" -> get("/api/v1/holds");
            default -> throw new IllegalArgumentException(endpoint);
        };
    }

    @Test
    void aCustomerSeesOnlyItsOwnOrganisationsNotificationsWhateverItAsksFor() throws Exception {
        UUID namibEmail = notification(TestCallers.NAMIB);
        UUID hanseaticEmail = notification(TestCallers.HANSEATIC);

        assertThat(listed(Who.CUSTOMER, null)).contains(namibEmail.toString()).doesNotContain(hanseaticEmail.toString());
        assertThat(listed(Who.CUSTOMER, TestCallers.HANSEATIC)).isEmpty();
        assertThat(listed(Who.AUDITOR, TestCallers.HANSEATIC)).contains(hanseaticEmail.toString())
                .doesNotContain(namibEmail.toString());
    }

    @Test
    void settingsBelongToTheTokensOrganisation() throws Exception {
        mockMvc.perform(put("/api/v1/notification-settings").with(Who.CUSTOMER.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"language\":\"de\",\"recipients\":[\"namib@example.org\"],\"kinds\":[\"shipment-booked\"]}"));
        String other = mockMvc.perform(get("/api/v1/notification-settings").with(Who.OTHER_CUSTOMER.token))
                .andReturn().getResponse().getContentAsString();

        assertThat(other).doesNotContain("namib@example.org")
                .contains(TestCallers.HANSEATIC.toString());
    }

    private UUID notification(UUID organization) {
        return notification(organization, "FAILED", 6);
    }

    private UUID notification(UUID organization, String status, int attempts) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO notifications (id, event_id, event_type, kind, organization_id, recipient, language,
                                           subject, body_text, body_html, status, attempts, next_attempt_at, created_at)
                VALUES (?, ?, 'shipment.booked', 'shipment-booked', ?, 'x@example.org', 'en', 's', 't', 'h', ?,
                        ?, now() + interval '1 day', now())
                """, id, UUID.randomUUID(), organization, status, attempts);
        return id;
    }

    private List<String> listed(Who who, UUID organization) throws Exception {
        var request = get("/api/v1/notifications").param("limit", "200").with(who.token);
        if (organization != null) {
            request.param("organizationId", organization.toString());
        }
        String body = mockMvc.perform(request).andReturn().getResponse().getContentAsString();
        List<String> ids = new ArrayList<>();
        JSON.readTree(body).path("items").forEach(item -> ids.add(item.path("id").asString()));
        return ids;
    }
}
