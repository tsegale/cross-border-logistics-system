package com.freightflow.banking.fx;

import com.freightflow.banking.security.Caller;
import com.freightflow.banking.fx.dto.CreateFxQuoteRequest;
import com.freightflow.banking.fx.dto.FxQuoteResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/fx-quotes")
@Tag(name = "FX Quotes", description = "Short-lived foreign exchange quotes for cross-currency transfers")
public class FxQuoteController {

    private final FxQuoteService fxQuoteService;

    public FxQuoteController(FxQuoteService fxQuoteService) {
        this.fxQuoteService = fxQuoteService;
    }

    @PostMapping
    @Operation(summary = "Create a short-lived FX quote")
    public ResponseEntity<FxQuoteResponse> createQuote(Caller caller, @Valid @RequestBody CreateFxQuoteRequest request) {
        FxQuoteResponse created = fxQuoteService.createQuote(caller, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch an FX quote by ID")
    public FxQuoteResponse getQuote(Caller caller, @PathVariable UUID id) {
        return fxQuoteService.getQuote(caller, id);
    }
}
