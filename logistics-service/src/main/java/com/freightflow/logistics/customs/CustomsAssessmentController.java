package com.freightflow.logistics.customs;

import com.freightflow.logistics.customs.CustomsAssessmentService.AssessmentRequest;
import com.freightflow.logistics.customs.CustomsAssessmentService.AssessmentResponse;
import com.freightflow.logistics.security.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Customs assessments, back office only (ADR 0036): one operator records, a different one confirms. */
@RestController
@RequestMapping("/api/v1/shipments/{id}/customs-assessment")
@Tag(name = "Customs", description = "The authority's assessment of duties and import VAT, and its settlement")
public class CustomsAssessmentController {

    private final CustomsAssessmentService assessments;

    public CustomsAssessmentController(CustomsAssessmentService assessments) {
        this.assessments = assessments;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record what customs assessed for a shipment whose duties FreightFlow collected")
    public AssessmentResponse record(Caller caller, @PathVariable("id") UUID id,
                                     @Valid @RequestBody AssessmentRequest request) {
        return assessments.record(caller, id, request);
    }

    @PostMapping("/confirmation")
    @Operation(summary = "Confirm another operator's assessment: banking pays customs and settles the difference")
    public AssessmentResponse confirm(Caller caller, @PathVariable("id") UUID id) {
        return assessments.confirm(caller, id);
    }

    @GetMapping
    @Operation(summary = "A shipment's customs assessment")
    public AssessmentResponse get(@PathVariable("id") UUID id) {
        return assessments.get(id);
    }
}
