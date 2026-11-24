package com.freightflow.banking.fx.rates;

import com.freightflow.banking.fx.rates.ReferenceRateExceptions.InvalidReferenceRateQueryException;
import com.freightflow.banking.fx.rates.ReferenceRateExceptions.ReferenceRateNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The stored reference rates (ADR 0034), for operators and auditors: what priced, and what waits in quarantine for a
 * RATE_CONFIRMATION approval. Customers see rates only through {@code /api/v1/fx-rates}.
 */
@RestController
@RequestMapping("/api/v1/reference-rates")
@Tag(name = "Reference rates", description = "Daily ECB reference rates, their checks and their quarantine (ADR 0034)")
public class ReferenceRateController {

    private static final int MAX_PAGE = 100;

    private final ReferenceRateService service;

    public ReferenceRateController(ReferenceRateService service) {
        this.service = service;
    }

    @Schema(description = "One day's reference rate from one source: 1 base = rate quote currency")
    public record ReferenceRateResponse(
            UUID id,
            @Schema(example = "ecb") String source,
            @Schema(example = "EUR") String baseCurrency,
            @Schema(example = "ZAR") String quoteCurrency,
            @Schema(example = "19.15130000") BigDecimal rate,
            LocalDate valueDate,
            Instant fetchedAt,
            ReferenceRateStatus status,
            @Schema(description = "The confirmed rate before it, which its day-on-day move was measured against",
                    nullable = true) BigDecimal previousRate,
            @Schema(description = "The second source it was checked against", nullable = true,
                    example = "bank-of-namibia") String crossCheckSource,
            @Schema(description = "That source's rate for the same day: NAD per EUR for the Bank of Namibia",
                    nullable = true) BigDecimal crossCheckRate,
            @Schema(description = "Every check it failed", nullable = true) String quarantineReason,
            @Schema(nullable = true) Instant decidedAt,
            @Schema(description = "The RATE_CONFIRMATION approval that decided it", nullable = true)
            UUID decidedApprovalId
    ) {
        static ReferenceRateResponse from(ReferenceRate rate) {
            return new ReferenceRateResponse(rate.getId(), rate.getSource(), rate.getBaseCurrency(),
                    rate.getQuoteCurrency(), rate.getRate(), rate.getValueDate(), rate.getFetchedAt(), rate.getStatus(),
                    rate.getPreviousRate(), rate.getCrossCheckSource(), rate.getCrossCheckRate(),
                    rate.getQuarantineReason(), rate.getDecidedAt(), rate.getDecidedApprovalId());
        }
    }

    public record ReferenceRateListResponse(List<ReferenceRateResponse> items) {
    }

    @GetMapping
    @Operation(summary = "Reference rates in one status, newest value date first",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The rates"),
                    @ApiResponse(responseCode = "400", description = "limit outside 1..100",
                            content = @Content(mediaType = "application/problem+json",
                                    schema = @Schema(implementation = ProblemDetail.class)))
            })
    public ReferenceRateListResponse list(
            @Parameter(description = "CONFIRMED, QUARANTINED or REJECTED", example = "QUARANTINED")
            @RequestParam(name = "status", defaultValue = "QUARANTINED") ReferenceRateStatus status,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw new InvalidReferenceRateQueryException("limit must be between 1 and " + MAX_PAGE);
        }
        return new ReferenceRateListResponse(service.byStatus(status, limit).stream()
                .map(ReferenceRateResponse::from).toList());
    }

    @GetMapping("/{id}")
    @Operation(summary = "One reference rate",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The rate"),
                    @ApiResponse(responseCode = "404", description = "No such rate",
                            content = @Content(mediaType = "application/problem+json",
                                    schema = @Schema(implementation = ProblemDetail.class)))
            })
    public ReferenceRateResponse get(@PathVariable("id") UUID id) {
        return service.find(id).map(ReferenceRateResponse::from)
                .orElseThrow(() -> new ReferenceRateNotFoundException(id));
    }
}
