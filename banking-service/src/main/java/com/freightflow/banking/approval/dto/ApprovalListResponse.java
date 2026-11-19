package com.freightflow.banking.approval.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Approval requests in one status, oldest first")
public record ApprovalListResponse(List<ApprovalResponse> items) {
}
