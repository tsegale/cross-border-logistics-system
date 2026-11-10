package com.freightflow.banking.transfer;

import com.freightflow.banking.account.InsufficientFundsException;
import com.freightflow.banking.common.ApiExceptionHandler;
import com.freightflow.banking.idempotency.IdempotencyKeyInFlightException;
import com.freightflow.banking.idempotency.IdempotencyPayloadMismatchException;
import com.freightflow.banking.idempotency.MissingIdempotencyKeyException;
import com.freightflow.banking.transfer.dto.TransferResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
@Import(ApiExceptionHandler.class)
class TransferControllerTest {

    private static final UUID ID = UUID.fromString("01930000-0000-7000-8000-000000003000");
    private static final String BODY = """
            {"sourceAccount":"FF83NAD000001000","targetAccount":"FF60NAD000001001","amount":2500.00,
             "currency":"NAD","reference":"FREIGHT-001"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferService transferService;

    @Test
    void createdTransferIs201WithLocation() throws Exception {
        given(transferService.createTransfer(any(), eq("key-1"))).willReturn(response());

        mockMvc.perform(transfer("key-1", BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/transfers/" + ID))
                .andExpect(jsonPath("$.sourceAmount").value(2500.00))
                .andExpect(jsonPath("$.reference").value("FREIGHT-001"));
    }

    @Test
    void invalidBodyIsRejectedBeforeTheServiceRuns() throws Exception {
        mockMvc.perform(transfer("key-1", """
                        {"sourceAccount":"","targetAccount":"FF60NAD000001001","amount":0,"currency":"NADX","reference":""}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/validation-failed"))
                .andExpect(jsonPath("$.errors.length()").value(4));
        verifyNoInteractions(transferService);
    }

    @Test
    void missingKeyIs400() throws Exception {
        given(transferService.createTransfer(any(), eq(null)))
                .willThrow(new MissingIdempotencyKeyException("Idempotency-Key header is required"));

        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/idempotency-key-missing"));
    }

    @Test
    void keyReusedWithAnotherBodyIs422() throws Exception {
        given(transferService.createTransfer(any(), eq("reused"))).willThrow(new IdempotencyPayloadMismatchException("reused"));

        mockMvc.perform(transfer("reused", BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/idempotency-key-payload-mismatch"));
    }

    @Test
    void keyInFlightIs409WithRetryAfter() throws Exception {
        given(transferService.createTransfer(any(), eq("busy"))).willThrow(new IdempotencyKeyInFlightException("busy"));

        mockMvc.perform(transfer("busy", BODY))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/idempotency-key-in-flight"));
    }

    @Test
    void insufficientFundsIs409() throws Exception {
        given(transferService.createTransfer(any(), eq("poor"))).willThrow(new InsufficientFundsException(
                "FF83NAD000001000", "NAD", new BigDecimal("10.00"), new BigDecimal("2500.00")));

        mockMvc.perform(transfer("poor", BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/insufficient-funds"));
    }

    @Test
    void unknownTransferIs404() throws Exception {
        given(transferService.getTransfer(ID)).willThrow(new TransferNotFoundException(ID));

        mockMvc.perform(get("/api/v1/transfers/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/transfer-not-found"));
    }

    private static RequestBuilder transfer(String key, String body) {
        return post("/api/v1/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static TransferResponse response() {
        return new TransferResponse(ID, UUID.randomUUID(), UUID.randomUUID(), "FF83NAD000001000",
                UUID.randomUUID(), "FF60NAD000001001", new BigDecimal("2500.00"), "NAD",
                new BigDecimal("2500.00"), "NAD", "FREIGHT-001", null, null,
                Instant.parse("2026-11-10T10:00:00Z"), List.of());
    }
}
