"""Builds logistics' UN/LOCODE migration from an official UNECE release (Phase 7, docs/locations/README.md).

UN/LOCODE carries no time zone, so each location gets one from IANA's zone.tab:
- the corridor's countries (NA, ZA, DE) give every location their principal zone;
- any other country with one zone gives every location that zone;
- in a country with several, the zone whose reference point is nearest the location's coordinates. Near a zone
  boundary this can be wrong, so those rows are counted in the migration's header;
- a location in a multi-zone country without coordinates is left out, and counted.

The subset is every location in the corridor's countries (NA, ZA, DE) and every seaport (function 1) elsewhere; a
code UNECE marked for deletion (change indicator X) is left out. The seeded locations are updated in place, so
shipments that name them keep their rows. A new release is a new migration, built the same way, never an edit.

    python scripts/build_unlocode_migration.py --release 2025-1 --tzdata 2026f \\
        --source local/unlocode --zones local/unlocode/zone.tab \\
        --out logistics-service/src/main/resources/db/migration/V19__unlocode_2025_1.sql

Standard library only: zone.tab is read from any tzdata (the migration header records which).
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import math
import re
from collections import Counter, defaultdict
from pathlib import Path

CORRIDOR = {"NA", "ZA", "DE"}
# The corridor's own countries take their principal zone whatever the coordinates: zone.tab also lists
# Europe/Busingen for one German exclave, whose reference point is nearest for all of south-west Germany. It has
# kept Berlin's offsets since 1980.
PRINCIPAL = {"NA": "Africa/Windhoek", "ZA": "Africa/Johannesburg", "DE": "Europe/Berlin"}
CODE = re.compile(r"^[A-Z]{2}[A-Z2-9]{3}$")
COORDINATES = re.compile(r"^(\d{2})(\d{2})([NS]) (\d{3})(\d{2})([EW])$")
BATCH = 500


def degrees(text: str) -> tuple[float, float] | None:
    match = COORDINATES.match(text.strip())
    if not match:
        return None
    lat = int(match[1]) + int(match[2]) / 60
    lon = int(match[4]) + int(match[5]) / 60
    return (-lat if match[3] == "S" else lat), (-lon if match[6] == "W" else lon)


def iso6709(text: str) -> tuple[float, float]:
    """zone.tab's +DDMM+DDDMM or +DDMMSS+DDDMMSS."""
    lat_part, lon_part = re.match(r"^([+-]\d+)([+-]\d+)$", text).groups()

    def convert(part: str, width: int) -> float:
        sign = -1 if part[0] == "-" else 1
        digits = part[1:]
        whole, rest = int(digits[:width]), digits[width:]
        minutes = int(rest[:2]) if rest else 0
        seconds = int(rest[2:4]) if len(rest) > 2 else 0
        return sign * (whole + minutes / 60 + seconds / 3600)

    return convert(lat_part, 2), convert(lon_part, 3)


def distance(a: tuple[float, float], b: tuple[float, float]) -> float:
    lat1, lon1, lat2, lon2 = map(math.radians, (*a, *b))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * math.asin(math.sqrt(h))


def sql(text: str) -> str:
    return "'" + text.replace("'", "''") + "'"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--release", required=True, help="the UNECE release, e.g. 2025-1")
    parser.add_argument("--tzdata", required=True, help="the tzdata release zone.tab came from, e.g. 2026f")
    parser.add_argument("--source", type=Path, required=True, help="the release's csv directory")
    parser.add_argument("--zones", type=Path, required=True, help="IANA zone.tab")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    zones: dict[str, list[tuple[str, tuple[float, float]]]] = defaultdict(list)
    for line in args.zones.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            country, coordinates, zone = line.split("\t")[:3]
            zones[country].append((zone, iso6709(coordinates)))

    files = sorted(args.source.glob("*CodeListPart*.csv"))
    evidence = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in files}
    rows, counts, seen = [], Counter(), set()
    for path in files:
        for record in csv.reader(path.open(encoding="utf-8")):
            if len(record) < 11 or not record[2]:
                continue
            change, country, place, name, _, _, function = record[:7]
            code = country + place
            if change == "X" or not CODE.match(code):
                counts["removed or malformed"] += 1
                continue
            if country not in CORRIDOR and function[:1] != "1":
                continue
            candidates = zones.get(country, [])
            if not candidates:
                counts["no zone for the country"] += 1
                continue
            if country in PRINCIPAL:
                zone = PRINCIPAL[country]
                counts["corridor country, principal zone"] += 1
            elif len(candidates) == 1:
                zone = candidates[0][0]
                counts["single-zone country"] += 1
            else:
                position = degrees(record[10])
                if position is None:
                    counts["multi-zone country, no coordinates: left out"] += 1
                    continue
                zone = min(candidates, key=lambda candidate: distance(position, candidate[1]))[0]
                counts["multi-zone country, nearest zone"] += 1
            if code in seen:
                # A few codes appear twice in a release (an alternative name); the first entry is the code's.
                counts["duplicate code: first kept"] += 1
                continue
            seen.add(code)
            rows.append((code, name.strip()[:120], country, zone))

    rows.sort()
    header = [
        f"-- UN/LOCODE {args.release} (UNECE), a subset: every location in NA, ZA and DE, and every seaport elsewhere.",
        "-- Built by scripts/build_unlocode_migration.py from the release's CSV files; never edited by hand. Time",
        f"-- zones from IANA zone.tab (tzdata {args.tzdata}); see docs/locations/README.md for the rules and terms.",
        "-- Evidence (SHA-256 of the source files):",
        *[f"--   {digest}  {name}" for name, digest in evidence.items()],
        "-- Rows: " + "; ".join(f"{label} {count}" for label, count in sorted(counts.items())) + f"; loaded {len(rows)}.",
        "",
        "ALTER TABLE locations ADD COLUMN unlocode_release VARCHAR(8);",
        "",
    ]
    body = []
    for start in range(0, len(rows), BATCH):
        values = ",\n".join(f"    ({sql(code)}, {sql(name)}, {sql(country)}, {sql(zone)}, {sql(args.release)})"
                            for code, name, country, zone in rows[start:start + BATCH])
        body.append("INSERT INTO locations (unlocode, name, country_code, iana_timezone, unlocode_release) VALUES\n"
                    + values + "\nON CONFLICT (unlocode) DO UPDATE SET name = EXCLUDED.name, "
                    "country_code = EXCLUDED.country_code, iana_timezone = EXCLUDED.iana_timezone, "
                    "unlocode_release = EXCLUDED.unlocode_release;\n")
    args.out.write_text("\n".join(header) + "\n".join(body), encoding="utf-8", newline="\n")
    print(f"{args.out}: {len(rows)} locations; " + "; ".join(f"{k} {v}" for k, v in sorted(counts.items())))


if __name__ == "__main__":
    main()
