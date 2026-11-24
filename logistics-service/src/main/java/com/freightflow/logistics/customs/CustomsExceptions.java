package com.freightflow.logistics.customs;

import java.time.LocalDate;
import java.util.UUID;

/** Errors of the customs estimate and settlement (ADR 0035, ADR 0036), each a problem type in docs/problems.md. */
public final class CustomsExceptions {

    private CustomsExceptions() {
    }

    /** Goods declared for a destination FreightFlow does not estimate duties for. */
    public static class CustomsMarketUnsupportedException extends RuntimeException {
        private final String country;

        public CustomsMarketUnsupportedException(String country) {
            super("Duties are estimated for imports into Germany (EU) and Namibia (SACU), not " + country);
            this.country = country;
        }

        public String getCountry() {
            return country;
        }
    }

    /** A code of the wrong length for the market: TARIC is 10 digits, the SACU tariff 8. */
    public static class InvalidHsCodeException extends RuntimeException {
        private final int line;
        private final String hsCode;

        public InvalidHsCodeException(int line, String hsCode, CustomsMarket market) {
            super("Goods line " + line + ": " + (market == CustomsMarket.EU
                    ? "imports into the EU are declared with the 10-digit TARIC code"
                    : "imports into Namibia are declared with the 8-digit SACU tariff heading") + ", not " + hsCode);
            this.line = line;
            this.hsCode = hsCode;
        }

        public int getLine() {
            return line;
        }

        public String getHsCode() {
            return hsCode;
        }
    }

    /** A well-formed code that the loaded tariff subset does not have. */
    public static class UnknownTariffCodeException extends RuntimeException {
        private final String hsCode;
        private final CustomsMarket market;
        private final LocalDate tariffDate;

        public UnknownTariffCodeException(String hsCode, CustomsMarket market, LocalDate tariffDate) {
            super("Tariff code " + hsCode + " is not in the " + market + " tariff loaded for " + tariffDate
                    + " (docs/tariffs/README.md)");
            this.hsCode = hsCode;
            this.market = market;
            this.tariffDate = tariffDate;
        }

        public String getHsCode() {
            return hsCode;
        }

        public CustomsMarket getMarket() {
            return market;
        }

        public LocalDate getTariffDate() {
            return tariffDate;
        }
    }

    /** banking has no usable rate to bring a declared value into the customs currency right now. */
    public static class CustomsConversionUnavailableException extends RuntimeException {
        private final String from;
        private final String to;

        public CustomsConversionUnavailableException(String from, String to) {
            super("No exchange rate from " + from + " to " + to + " is available right now to estimate duties; "
                    + "declare the values in " + to + " or try again later");
            this.from = from;
            this.to = to;
        }

        public String getFrom() {
            return from;
        }

        public String getTo() {
            return to;
        }
    }

    public static class CustomsAssessmentNotFoundException extends RuntimeException {
        private final UUID shipmentId;

        public CustomsAssessmentNotFoundException(UUID shipmentId) {
            super("Shipment " + shipmentId + " has no customs assessment");
            this.shipmentId = shipmentId;
        }

        public UUID getShipmentId() {
            return shipmentId;
        }
    }

    /** Recorded already, confirmed already, not collected, or not captured yet. */
    public static class CustomsAssessmentConflictException extends RuntimeException {
        private final UUID shipmentId;
        private final String reason;

        public CustomsAssessmentConflictException(UUID shipmentId, String reason) {
            super("Shipment " + shipmentId + ": " + reason);
            this.shipmentId = shipmentId;
            this.reason = reason;
        }

        public UUID getShipmentId() {
            return shipmentId;
        }

        public String getReason() {
            return reason;
        }
    }

    /** The operator who recorded the assessment tried to confirm it (ADR 0021). */
    public static class CustomsFourEyesException extends RuntimeException {
        private final UUID shipmentId;

        public CustomsFourEyesException(UUID shipmentId) {
            super("The customs assessment of shipment " + shipmentId
                    + " must be confirmed by an operator other than the one who recorded it");
            this.shipmentId = shipmentId;
        }

        public UUID getShipmentId() {
            return shipmentId;
        }
    }
}
