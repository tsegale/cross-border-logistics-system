package com.freightflow.logistics.migration;

import com.freightflow.logistics.support.SharedContainers;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** V2 runs against data written under V1: a quote priced with the wrong duty base must not stay bookable. */
@Testcontainers(disabledWithoutDocker = true)
class DropImportDutiesMigrationTest {

    private static final String SCHEMA = "migration_v2_test";

    @Test
    void v2ExpiresQuotesThatCarriedADutyAndDropsTheDutyColumns() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                SharedContainers.POSTGRES.getJdbcUrl(), SharedContainers.POSTGRES.getUsername(),
                SharedContainers.POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");

        flyway(dataSource, "1").migrate();
        Instant validUntil = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        insertQuote(jdbc, "01990000-0000-7000-8000-000000000001", "15.0000", validUntil);
        insertQuote(jdbc, "01990000-0000-7000-8000-000000000002", "0.0000", validUntil);

        flyway(dataSource, "2").migrate();

        Timestamp dutyQuoteValidUntil = jdbc.queryForObject(
                "SELECT valid_until FROM " + SCHEMA + ".freight_quotes WHERE id = '01990000-0000-7000-8000-000000000001'",
                Timestamp.class);
        Timestamp cleanQuoteValidUntil = jdbc.queryForObject(
                "SELECT valid_until FROM " + SCHEMA + ".freight_quotes WHERE id = '01990000-0000-7000-8000-000000000002'",
                Timestamp.class);
        assertThat(dutyQuoteValidUntil.toInstant()).isBefore(validUntil.minus(23, ChronoUnit.HOURS));
        assertThat(cleanQuoteValidUntil.toInstant()).isEqualTo(validUntil);

        Integer dutyColumns = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = ? AND column_name IN ('import_customs_duty_charge', 'import_duty_percent')
                """, Integer.class, SCHEMA);
        assertThat(dutyColumns).isZero();
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).target(target).load();
    }

    private static void insertQuote(JdbcTemplate jdbc, String id, String duty, Instant validUntil) {
        jdbc.update("INSERT INTO " + SCHEMA + ".freight_quotes (id, origin_unlocode, destination_unlocode, mode, "
                + "incoterm, billing_unit, package_count, actual_weight_kg, volume_cbm, volumetric_weight_kg, "
                + "chargeable_weight_kg, chargeable_units, tariff_currency, snapshot_rate_per_unit, "
                + "snapshot_minimum_charge, snapshot_fuel_surcharge_pct, snapshot_fx_rate, currency, "
                + "base_freight_charge, fuel_surcharge, origin_handling_charge, destination_handling_charge, "
                + "import_customs_duty_charge, total_amount, transit_days_min, transit_days_max, valid_until, created_at) "
                + "VALUES (?::uuid, 'NAWVB', 'DEHAM', 'SEA_LCL', 'DDP', 'WM_TON', 1, 1000, 3.6, 3600, 3600, 3.6, "
                + "'EUR', 95, 180, 12.5, 1, 'EUR', 342, 42.75, 65, 85, ?::numeric, 600, 18, 24, ?, now())",
                id, duty, Timestamp.from(validUntil));
    }
}
