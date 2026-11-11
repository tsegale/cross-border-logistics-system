package com.freightflow.logistics.shipment;

import com.freightflow.logistics.support.IntegrationTest;
import com.freightflow.logistics.support.LogisticsFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static com.freightflow.logistics.support.LogisticsFixtures.PAYER_ACCOUNT;
import static com.freightflow.logistics.support.LogisticsFixtures.bookShipmentRequest;
import static com.freightflow.logistics.support.LogisticsFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShipmentBookingIntegrationTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String quoteId;

    @BeforeEach
    void quote() throws Exception {
        quoteId = new LogisticsFixtures(mockMvc).createQuote();
    }

    @Test
    void bookingStartsAwaitingPaymentWithVersionZero() throws Exception {
        MvcResult res = mockMvc.perform(bookShipmentRequest(quoteId, "book-" + UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.quoteId").value(quoteId))
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.payerAccountNumber").value(PAYER_ACCOUNT))
                .andExpect(jsonPath("$.quotedTotalAmount").value(524.75))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();
        assertThat(TrackingNumber.isValid(json(res).get("trackingNumber").asString())).isTrue();
    }

    @Test
    void sameKeyAndBodyReplaysTheOriginalResponse() throws Exception {
        String key = "replay-" + UUID.randomUUID();
        JsonNode first = json(mockMvc.perform(bookShipmentRequest(quoteId, key)).andExpect(status().isCreated()).andReturn());

        mockMvc.perform(bookShipmentRequest(quoteId, key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(first.get("id").asString()))
                .andExpect(jsonPath("$.trackingNumber").value(first.get("trackingNumber").asString()));
    }

    @Test
    void sameKeyWithADifferentBodyIs422() throws Exception {
        String key = "mismatch-" + UUID.randomUUID();
        mockMvc.perform(bookShipmentRequest(quoteId, key)).andExpect(status().isCreated());
        String otherQuote = new LogisticsFixtures(mockMvc).createQuote();

        mockMvc.perform(bookShipmentRequest(otherQuote, key))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/idempotency-key-payload-mismatch"));
    }

    @Test
    void concurrentRequestsWithOneKeyBookOneShipment() throws Exception {
        String key = "concurrent-" + UUID.randomUUID();
        int callers = 12;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(bookShipmentRequest(quoteId, key)).andReturn();
                }));
            }
            start.countDown();
            List<JsonNode> bodies = new ArrayList<>();
            for (Future<MvcResult> f : futures) {
                MvcResult r = f.get();
                assertThat(r.getResponse().getStatus()).isEqualTo(201);
                bodies.add(json(r));
            }
            Set<String> ids = bodies.stream().map(b -> b.get("id").asString()).collect(Collectors.toSet());
            assertThat(ids).hasSize(1);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM shipments WHERE quote_id = ?::uuid",
                Integer.class, quoteId)).isEqualTo(1);
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        mockMvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON).content(body(PAYER_ACCOUNT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/idempotency-key-missing"));
    }

    @Test
    void mistypedPayerAccountIs400() throws Exception {
        char digit = PAYER_ACCOUNT.charAt(2);
        String wrong = PAYER_ACCOUNT.substring(0, 2) + (digit == '9' ? '0' : (char) (digit + 1)) + PAYER_ACCOUNT.substring(3);
        mockMvc.perform(post("/api/v1/shipments")
                        .header("Idempotency-Key", "bad-acc-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(wrong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-account-number"));
    }

    @Test
    void expiredQuoteIs409() throws Exception {
        jdbcTemplate.update("UPDATE freight_quotes SET valid_until = created_at - INTERVAL '1 hour' WHERE id = ?::uuid",
                quoteId);

        mockMvc.perform(bookShipmentRequest(quoteId, "expired-" + UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/quote-expired"));
    }

    @Test
    void plannedPickupIsRequiredAndMustBeBetweenTodayAndTheHorizon() throws Exception {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        for (String pickup : new String[] {null, today.minusDays(1).toString(), today.plusDays(91).toString()}) {
            String body = body(PAYER_ACCOUNT).replace("\"plannedPickupDate\":\"" + LogisticsFixtures.defaultPickup() + "\"",
                    pickup == null ? "\"plannedPickupDate\":null" : "\"plannedPickupDate\":\"" + pickup + "\"");
            mockMvc.perform(post("/api/v1/shipments")
                            .header("Idempotency-Key", "pickup-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(bookShipmentRequest(quoteId, "pickup-ok-" + UUID.randomUUID(), today.plusDays(90)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plannedPickupDate").value(today.plusDays(90).toString()));
    }

    private String body(String payerAccount) {
        return """
                {"quoteId":"%s","shipper":{"name":"a","address":"b","contact":"c"},
                 "consignee":{"name":"a","address":"b","contact":"c"},"payerAccountNumber":"%s",
                 "goodsDescription":"fish","declaredValue":100.00,"plannedPickupDate":"%s"}
                """.formatted(quoteId, payerAccount, LogisticsFixtures.defaultPickup());
    }
}
