package com.freightflow.notification.notify;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Optional;

/**
 * What an organisation can be emailed about (ADR 0027); each has a template and can be switched off in the settings.
 */
public enum NotificationKind {
    /** {@code shipment.booked}: "Sendung gebucht" / "Shipment booked". */
    SHIPMENT_BOOKED("shipment-booked"),
    /** {@code shipment.status-changed} to a status a customer cares about (see {@link #EMAILED_STATUSES}). */
    SHIPMENT_STATUS("shipment-status"),
    /** {@code shipment.payment-failed}. */
    PAYMENT_FAILED("payment-failed"),
    /** {@code hold.captured}: the charge was taken. */
    PAYMENT_CAPTURED("payment-captured"),
    /** {@code hold.released} or {@code hold.expired}: the reservation is given back. */
    PAYMENT_RELEASED("payment-released");

    /** Status changes worth an email; the in-between milestones are on the tracking page. */
    public static final java.util.Set<String> EMAILED_STATUSES = java.util.Set.of(
            "BOOKED", "PICKED_UP", "CUSTOMS_HOLD", "OUT_FOR_DELIVERY", "DELIVERED", "CANCELLED", "EXCEPTION");

    private final String code;

    NotificationKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    public static Optional<NotificationKind> of(String code) {
        return Arrays.stream(values()).filter(kind -> kind.code.equals(code)).findFirst();
    }
}
