package com.freightflow.logistics.banking;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.freightflow.logistics.support.BankingStubs;
import com.freightflow.logistics.support.BankingStubs.Interaction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumer side of the banking contract (ADR 0014): every request logistics sends to banking and every response
 * its tests stub must be allowed by banking's committed OpenAPI document. banking's own OpenApiContractTest keeps
 * that document equal to what banking serves.
 */
class BankingContractTest {

    static final Path CONTRACT = Path.of("..", "docs", "api", "banking-openapi.json");
    private static OpenApiInteractionValidator validator;

    @BeforeAll
    static void loadContract() throws Exception {
        assertThat(Files.exists(CONTRACT)).as("missing %s", CONTRACT.toAbsolutePath()).isTrue();
        validator = OpenApiInteractionValidator.createForInlineApiSpecification(Files.readString(CONTRACT)).build();
    }

    static Stream<Interaction> interactions() {
        return BankingStubs.all().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("interactions")
    void stubIsAllowedByBankingsContract(Interaction interaction) {
        ValidationReport report = validator.validate(request(interaction), response(interaction));

        assertThat(report.getMessages()).as(interaction.name()).isEmpty();
    }

    @Test
    void aStubThatBankingWouldNeverSendIsCaught() {
        Interaction wrong = new Interaction("hold without its id", "POST", "/api/v1/holds", BankingStubs.HOLD_REQUEST,
                201, "application/json", "{\"status\":\"ACTIVE\",\"amount\":\"not a number\"}");

        assertThat(validator.validate(request(wrong), response(wrong)).hasErrors()).isTrue();
    }

    @Test
    void aHoldRequestWithoutItsIdempotencyKeyIsCaught() {
        Request withoutKey = SimpleRequest.Builder.post("/api/v1/holds")
                .withContentType("application/json")
                .withBody(BankingStubs.HOLD_REQUEST)
                .build();

        assertThat(validator.validateRequest(withoutKey).hasErrors()).isTrue();
    }

    private static Request request(Interaction interaction) {
        String path = interaction.path();
        String query = null;
        int q = path.indexOf('?');
        if (q >= 0) {
            query = path.substring(q + 1);
            path = path.substring(0, q);
        }
        SimpleRequest.Builder builder = new SimpleRequest.Builder(interaction.method(), path)
                .withHeader("Accept", "application/json, application/problem+json");
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                builder.withQueryParam(kv[0], kv[1]);
            }
        }
        if ("POST".equals(interaction.method())) {
            builder.withHeader("Idempotency-Key", "shipment-01a1c900-0000-7000-8000-0000000000bb-authorise");
        }
        if (interaction.requestBody() != null) {
            builder.withContentType("application/json").withBody(interaction.requestBody());
        }
        return builder.build();
    }

    private static SimpleResponse response(Interaction interaction) {
        return SimpleResponse.Builder.status(interaction.status())
                .withContentType(interaction.contentType())
                .withBody(interaction.responseBody())
                .build();
    }
}
