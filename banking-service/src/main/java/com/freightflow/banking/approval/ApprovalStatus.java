package com.freightflow.banking.approval;

/** PENDING until a second operator decides, or until it expires. Every other status is final. */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED
}
