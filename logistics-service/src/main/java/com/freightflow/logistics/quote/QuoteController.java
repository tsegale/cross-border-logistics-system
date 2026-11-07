package com.freightflow.logistics.quote;

import com.freightflow.logistics.quote.dto.CreateQuoteRequest;
import com.freightflow.logistics.quote.dto.QuoteResponse;
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
@RequestMapping("/api/v1/quotes")
@Tag(name = "Freight Quotes", description = "Corridor freight quotes with chargeable weight and Incoterms 2020 breakdown")
public class QuoteController {

    private final QuoteService quoteService;

    public QuoteController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    @PostMapping
    @Operation(summary = "Calculate and persist a freight quote with chargeable weight")
    public ResponseEntity<QuoteResponse> createQuote(@Valid @RequestBody CreateQuoteRequest request) {
        QuoteResponse created = quoteService.createQuote(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a freight quote by ID")
    public QuoteResponse getQuote(@PathVariable UUID id) {
        return quoteService.getQuote(id);
    }
}
