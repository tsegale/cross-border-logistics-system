package com.freightflow.logistics.quote;

import com.freightflow.logistics.quote.dto.PackageSpecDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Industry chargeable-weight calculator for {@code AIR} and {@code SEA_LCL} freight (ADR 0008):
 * <ul>
 *   <li><b>Air freight ({@code AIR})</b>: per <b>IATA Cargo Services Conference Resolution 502</b>,
 *       volumetric weight is \(\text{volume in cm}^3 / 6{,}000\) (equivalent to \(166.67\text{ kg/m}^3\)),
 *       and chargeable weight is \(\max(\text{actual gross kg}, \text{volumetric kg})\).</li>
 *   <li><b>Ocean LCL ({@code SEA_LCL})</b>: billed per <b>W/M (Weight or Measure) Revenue Ton</b>,
 *       \(\max(\text{gross weight in metric tons}, \text{volume in m}^3)\), where \(1\text{ CBM} = 1{,}000\text{ kg}\)
 *       volumetric equivalent.</li>
 * </ul>
 */
public final class ChargeableWeightCalculator {

    /** IATA Resolution 502 air cargo volumetric divisor: 6,000 cm³ per kg. */
    public static final BigDecimal AIR_VOLUMETRIC_DIVISOR_CM3_PER_KG = new BigDecimal("6000");

    /** Cubic centimetres per cubic metre (CBM). */
    public static final BigDecimal CM3_PER_CBM = new BigDecimal("1000000");

    /** Kilograms per metric ton (1,000 kg = 1 W/M weight ton). */
    public static final BigDecimal KG_PER_METRIC_TON = new BigDecimal("1000");

    private ChargeableWeightCalculator() {
    }

    public record CalculationResult(
            int totalPieces,
            BigDecimal actualWeightKg,
            BigDecimal volumeCbm,
            BigDecimal volumetricWeightKg,
            BigDecimal chargeableWeightKg,
            BillingUnit billingUnit,
            BigDecimal chargeableUnits
    ) {
    }

    public static CalculationResult calculate(TransportMode mode, List<PackageSpecDto> packages) {
        if (packages == null || packages.isEmpty()) {
            throw new IllegalArgumentException("At least one package is required");
        }

        int totalPieces = 0;
        BigDecimal totalActualWeightKg = BigDecimal.ZERO;
        BigDecimal totalVolumeCm3 = BigDecimal.ZERO;

        for (PackageSpecDto pkg : packages) {
            if (pkg.lengthCm() == null || pkg.lengthCm().compareTo(BigDecimal.ZERO) <= 0
                    || pkg.widthCm() == null || pkg.widthCm().compareTo(BigDecimal.ZERO) <= 0
                    || pkg.heightCm() == null || pkg.heightCm().compareTo(BigDecimal.ZERO) <= 0
                    || pkg.weightKg() == null || pkg.weightKg().compareTo(BigDecimal.ZERO) <= 0
                    || pkg.quantity() <= 0) {
                throw new IllegalArgumentException("Package dimensions, weight and quantity must all be positive");
            }
            BigDecimal qty = BigDecimal.valueOf(pkg.quantity());
            totalPieces += pkg.quantity();
            totalActualWeightKg = totalActualWeightKg.add(pkg.weightKg().multiply(qty));
            BigDecimal singlePackageCm3 = pkg.lengthCm().multiply(pkg.widthCm()).multiply(pkg.heightCm());
            totalVolumeCm3 = totalVolumeCm3.add(singlePackageCm3.multiply(qty));
        }

        BigDecimal actualWeightKg = totalActualWeightKg.setScale(3, RoundingMode.HALF_EVEN);
        BigDecimal volumeCbm = totalVolumeCm3.divide(CM3_PER_CBM, 4, RoundingMode.HALF_EVEN);
        if (volumeCbm.compareTo(BigDecimal.ZERO) == 0) {
            volumeCbm = new BigDecimal("0.0001");
        }

        if (mode == TransportMode.AIR) {
            BigDecimal volumetricWeightKg = totalVolumeCm3.divide(
                    AIR_VOLUMETRIC_DIVISOR_CM3_PER_KG, 3, RoundingMode.HALF_EVEN);
            BigDecimal chargeableWeightKg = actualWeightKg.max(volumetricWeightKg);
            return new CalculationResult(
                    totalPieces,
                    actualWeightKg,
                    volumeCbm,
                    volumetricWeightKg,
                    chargeableWeightKg,
                    BillingUnit.KG,
                    chargeableWeightKg.setScale(4, RoundingMode.HALF_EVEN)
            );
        }

        // SEA_LCL: W/M Revenue Ton = max(actualWeightKg / 1000, volumeCbm)
        BigDecimal weightTons = actualWeightKg.divide(KG_PER_METRIC_TON, 4, RoundingMode.HALF_EVEN);
        BigDecimal chargeableWmTons = weightTons.max(volumeCbm);
        BigDecimal volumetricWeightKg = volumeCbm.multiply(KG_PER_METRIC_TON).setScale(3, RoundingMode.HALF_EVEN);
        BigDecimal chargeableWeightKg = actualWeightKg.max(volumetricWeightKg);

        return new CalculationResult(
                totalPieces,
                actualWeightKg,
                volumeCbm,
                volumetricWeightKg,
                chargeableWeightKg,
                BillingUnit.WM_TON,
                chargeableWmTons
        );
    }
}
