package com.freightflow.logistics.location;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.ZoneId;
import java.util.Objects;

@Entity
@Table(name = "locations")
public class Location {

    @Id
    @Column(name = "unlocode", length = 5, nullable = false, updatable = false)
    private String unlocode;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Column(name = "country_code", length = 2, nullable = false)
    private String countryCode;

    @Column(name = "iana_timezone", length = 64, nullable = false)
    private String ianaTimezone;

    protected Location() {
    }

    public Location(String unlocode, String name, String countryCode, String ianaTimezone) {
        this.unlocode = Objects.requireNonNull(unlocode, "unlocode");
        this.name = Objects.requireNonNull(name, "name");
        this.countryCode = Objects.requireNonNull(countryCode, "countryCode");
        this.ianaTimezone = Objects.requireNonNull(ianaTimezone, "ianaTimezone");
    }

    public String getUnlocode() {
        return unlocode;
    }

    public String getName() {
        return name;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public String getIanaTimezone() {
        return ianaTimezone;
    }

    public ZoneId zoneId() {
        return ZoneId.of(ianaTimezone);
    }
}
