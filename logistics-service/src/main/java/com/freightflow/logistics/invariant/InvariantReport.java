package com.freightflow.logistics.invariant;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One invariant audit (ADR 0044): each invariant, how many rows break it, and the first few of them. {@code captures}
 * are the captures banking confirmed, per charge currency, which {@code scripts/audit_invariants.py} compares with
 * banking's captured holds.
 */
@Schema(description = "logistics' invariants at one instant, each with the rows that break it")
public record InvariantReport(String service, Instant checkedAt, List<Invariant> invariants,
                              Map<String, CaptureTotal> captures) {

    public record Invariant(String name, long violations, List<String> examples) {

        static Invariant of(String name, List<String> rows, int examples) {
            return new Invariant(name, rows.size(), List.copyOf(rows.subList(0, Math.min(examples, rows.size()))));
        }
    }

    /** Captures banking confirmed in one charge currency: how many, and their amounts summed. */
    public record CaptureTotal(long count, BigDecimal amount) {
    }

    @JsonProperty
    @Schema(description = "True when no invariant is broken")
    public boolean holds() {
        return invariants.stream().allMatch(invariant -> invariant.violations() == 0);
    }

    @JsonProperty
    @Schema(description = "The names of the broken invariants")
    public List<String> broken() {
        return invariants.stream().filter(invariant -> invariant.violations() > 0).map(Invariant::name).toList();
    }
}
