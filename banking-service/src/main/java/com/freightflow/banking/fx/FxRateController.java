package com.freightflow.banking.fx;

import com.freightflow.banking.fx.dto.FxRatesResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fx-rates")
@Tag(name = "FX Rates", description = "Read-only indicative rates; banking-service is the only owner of FX")
public class FxRateController {

    private final FxRateService fxRateService;

    public FxRateController(FxRateService fxRateService) {
        this.fxRateService = fxRateService;
    }

    @GetMapping
    @Operation(
            summary = "Indicative rates from a base currency to every other supported currency",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Indicative rates",
                            content = @Content(schema = @Schema(implementation = FxRatesResponse.class))),
                    @ApiResponse(responseCode = "400", description = "Unsupported base currency",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public FxRatesResponse getRates(
            @Parameter(description = "ISO 4217 base currency", example = "EUR")
            @RequestParam("base") String base) {
        return fxRateService.indicativeRates(base);
    }
}
