package com.freightflow.logistics.location;

import com.freightflow.logistics.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UN/LOCODE 2025-1 as V19 loads it (docs/locations/README.md): the corridor's places in their principal zones, the
 * world's seaports in the zone nearest their coordinates, the seeded locations updated in place, and every zone one
 * the JVM knows.
 */
class UnlocodeIntegrationTest extends IntegrationTest {

    @Autowired
    private LocationRepository locations;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void theReleaseIsLoadedWithAZoneForEveryLocation() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM locations WHERE unlocode_release = '2025-1'", Integer.class)).isEqualTo(25_797);
        Map<String, String> expected = Map.of(
                "NAWVB", "Africa/Windhoek", "NAWDH", "Africa/Windhoek", "ZADUR", "Africa/Johannesburg",
                "DEHAM", "Europe/Berlin", "USNYC", "America/New_York", "USLAX", "America/Los_Angeles",
                "AUPER", "Australia/Perth", "AUSYD", "Australia/Sydney", "BRSSZ", "America/Sao_Paulo");
        expected.forEach((code, zone) -> assertThat(locations.findById(code)).as(code)
                .hasValueSatisfying(location -> assertThat(location.getIanaTimezone()).isEqualTo(zone)));

        List<String> zones = jdbcTemplate.queryForList("SELECT DISTINCT iana_timezone FROM locations", String.class);
        assertThat(zones).allSatisfy(zone -> assertThat(ZoneId.of(zone)).isNotNull());
        // The seeded locations were updated in place: none is left without the release.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM locations WHERE unlocode_release IS NULL", Integer.class)).isZero();
    }
}
