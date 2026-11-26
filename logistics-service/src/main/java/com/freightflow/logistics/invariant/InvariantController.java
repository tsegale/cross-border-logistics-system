package com.freightflow.logistics.invariant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Staff audit logistics' invariants on demand (ADR 0044); operators and auditors only (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/logistics/invariants")
@Tag(name = "Invariants", description = "Shipments paid once, the outbox in order")
public class InvariantController {

    private final InvariantAuditor auditor;

    public InvariantController(InvariantAuditor auditor) {
        this.auditor = auditor;
    }

    @GetMapping
    @Operation(summary = "Audit logistics' invariants now: delivered shipments paid once, the outbox in order")
    public InvariantReport audit() {
        // Through the auditor, so an audit an operator asks for updates the gauges too.
        return auditor.audit();
    }
}
