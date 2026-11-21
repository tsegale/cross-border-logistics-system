package com.freightflow.notification.settings;

import com.freightflow.notification.security.Caller;
import com.freightflow.notification.support.IntegrationTest;
import com.freightflow.notification.support.TestCallers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET/PUT /api/v1/notification-settings} (ADR 0027): defaults, validation, versions and who changed them. */
class SettingsIntegrationTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void anOrganisationWithoutSettingsGetsTheDefaultsAndNoRecipients() throws Exception {
        mockMvc.perform(get("/api/v1/notification-settings").with(customer(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.language").value("en"))
                .andExpect(jsonPath("$.recipients").isEmpty())
                .andExpect(jsonPath("$.kinds.length()").value(5));
    }

    @Test
    void savedSettingsAreNormalisedVersionedAndRecordWhoSavedThem() throws Exception {
        UUID org = UUID.randomUUID();
        mockMvc.perform(put("/api/v1/notification-settings").with(customer(org))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"language":"DE","recipients":["Ops@Example.org","ops@example.org"],
                                 "kinds":["shipment-booked","payment-released","shipment-booked"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.language").value("de"))
                .andExpect(jsonPath("$.recipients.length()").value(1))
                .andExpect(jsonPath("$.recipients[0]").value("ops@example.org"))
                .andExpect(jsonPath("$.kinds.length()").value(2));

        mockMvc.perform(put("/api/v1/notification-settings").with(customer(org)).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"en\",\"recipients\":[],\"kinds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT version, updated_by_subject, updated_by_client_id FROM notification_settings "
                        + "WHERE organization_id = ?", org);
        assertThat(row.get("version")).isEqualTo(2L);
        assertThat(row.get("updated_by_subject")).isEqualTo("customer-" + org);
        assertThat(row.get("updated_by_client_id")).isEqualTo("freightflow-web");
    }

    @Test
    void theTimeZoneIsSavedKeptWhenLeftOutAndChecked() throws Exception {
        UUID org = UUID.randomUUID();
        mockMvc.perform(put("/api/v1/notification-settings").with(customer(org))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"en\",\"recipients\":[],\"kinds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("UTC"));
        mockMvc.perform(put("/api/v1/notification-settings").with(customer(org))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"en\",\"timeZone\":\"Africa/Windhoek\",\"recipients\":[],\"kinds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("Africa/Windhoek"));
        // A client that does not send the field keeps what was saved.
        mockMvc.perform(put("/api/v1/notification-settings").with(customer(org))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"de\",\"recipients\":[],\"kinds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("Africa/Windhoek"));
        for (String invalid : new String[] {"+02:00", "Europe/Atlantis", ""}) {
            mockMvc.perform(put("/api/v1/notification-settings").with(customer(org))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"language\":\"en\",\"timeZone\":\"" + invalid
                                    + "\",\"recipients\":[],\"kinds\":[]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(
                            "https://freightflow.dev/problems/invalid-notification-settings"));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT time_zone FROM notification_settings WHERE organization_id = ?",
                String.class, org)).isEqualTo("Africa/Windhoek");
    }

    @Test
    void aStaleIfMatchIs412() throws Exception {
        UUID org = UUID.randomUUID();
        String body = "{\"language\":\"en\",\"recipients\":[],\"kinds\":[]}";
        mockMvc.perform(put("/api/v1/notification-settings").with(customer(org))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/notification-settings").with(customer(org)).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/precondition-failed"))
                .andExpect(jsonPath("$.currentETag").value("\"1\""));
    }

    @Test
    void invalidSettingsAreProblems() throws Exception {
        UUID org = UUID.randomUUID();
        mockMvc.perform(put("/api/v1/notification-settings").with(customer(org))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"fr\",\"recipients\":[],\"kinds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-notification-settings"));
        mockMvc.perform(put("/api/v1/notification-settings").with(customer(org))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"en\",\"recipients\":[],\"kinds\":[\"newsletter\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-notification-settings"));
        mockMvc.perform(put("/api/v1/notification-settings").with(customer(org))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"en\",\"recipients\":[\"not an address\"],\"kinds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/validation-failed"));
        mockMvc.perform(put("/api/v1/notification-settings").with(customer(org))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"en\",\"recipients\":[" + "\"a@example.org\",".repeat(10)
                                + "\"b@example.org\"],\"kinds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aCustomerTokenWithoutAnOrganisationIs403() throws Exception {
        Caller orphan = new Caller("orphan", "freightflow-web", null, Set.of(Caller.CUSTOMER), Set.of());
        mockMvc.perform(get("/api/v1/notification-settings").with(TestCallers.as(orphan)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/forbidden"));
    }

    private static RequestPostProcessor customer(UUID org) {
        return TestCallers.as(new Caller("customer-" + org, "freightflow-web", org, Set.of(Caller.CUSTOMER),
                Set.of()));
    }
}
