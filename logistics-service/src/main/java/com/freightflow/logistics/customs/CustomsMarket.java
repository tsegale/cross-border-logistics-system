package com.freightflow.logistics.customs;

import java.util.Optional;
import java.util.Set;

/**
 * Where goods are imported, and so which tariff, valuation and import VAT apply (ADR 0035).
 * <ul>
 *   <li>{@link #EU}: into Germany. TARIC 10-digit codes; customs value CIF at the EU border (Union Customs Code,
 *       Regulation (EU) No 952/2013, Articles 70 and 71(1)(e)); import VAT under § 11 and § 12 UStG, in EUR.</li>
 *   <li>{@link #SACU}: into Namibia. The SACU tariff's 8-digit headings; customs value FOB (NamRA's valuation
 *       guidance: freight and insurance are deducted); import VAT under sections 6 and 12 of the Value-Added Tax
 *       Act 10 of 2000, in NAD.</li>
 * </ul>
 */
public enum CustomsMarket {
    EU("EUR", 10),
    SACU("NAD", 8);

    /** EU member states, for the EU-origin preference into SACU (ISO 3166-1 alpha-2, Greece as GR). */
    public static final Set<String> EU_MEMBER_STATES = Set.of("AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI",
            "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE");

    private final String currency;
    private final int codeLength;

    CustomsMarket(String currency, int codeLength) {
        this.currency = currency;
        this.codeLength = codeLength;
    }

    /** The currency duties and import VAT are assessed in. */
    public String currency() {
        return currency;
    }

    /** The length of the tariff codes goods are declared with. */
    public int codeLength() {
        return codeLength;
    }

    /** The market a destination country imports into; empty where FreightFlow does not estimate duties. */
    public static Optional<CustomsMarket> importingInto(String countryCode) {
        return switch (countryCode) {
            case "DE" -> Optional.of(EU);
            case "NA" -> Optional.of(SACU);
            default -> Optional.empty();
        };
    }

    /** Whether goods of this origin may claim a preference loaded for {@code origins} ("NA", or "EU"). */
    public static boolean originMatches(String origins, String originCountry) {
        for (String origin : origins.split(",")) {
            String trimmed = origin.trim();
            if (trimmed.equals(originCountry) || trimmed.equals("EU") && EU_MEMBER_STATES.contains(originCountry)) {
                return true;
            }
        }
        return false;
    }
}
