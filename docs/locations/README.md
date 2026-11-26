# Locations: UN/LOCODE

logistics names every place by its UN/LOCODE (`locations.unlocode`) and keeps each place's IANA time zone, so a
carrier's local event time becomes an instant and a planned pickup date is read in the place's own calendar.

## The loaded release

| | |
| --- | --- |
| Source | UNECE, UN/LOCODE release 2025-1, the CSV files of the official download (`UNLOCODE CodeListPart1.csv` to `Part3.csv`) |
| Migration | `logistics-service/src/main/resources/db/migration/V19__unlocode_2025_1.sql`, built by `scripts/build_unlocode_migration.py`; never edited by hand |
| Evidence | the SHA-256 of each source file, in the migration's header |
| Time zones | IANA `zone.tab`, tzdata 2026f (from the pinned `postgres:17-alpine` image) |
| Loaded | 25,797 locations, every row marked `unlocode_release = '2025-1'` |
| Built | 2026-11-26 |

**The subset.**
- **Every location in the corridor's countries**: Namibia, South Africa and Germany.
- **Every seaport elsewhere**: function code 1 in position one.
- **Left out:** codes UNECE marked for deletion (change indicator `X`).
- **Already seeded:** the eight seeded locations (V1) are updated in place, so shipments that name them keep their
  rows.

**Time zones.** UN/LOCODE has none, so each location takes one from `zone.tab`:

| Rule | Rows |
| --- | ---: |
| The corridor's countries take their principal zone (Africa/Windhoek, Africa/Johannesburg, Europe/Berlin) | 10,863 |
| Any other country with one zone: that zone | 11,016 |
| A country with several zones: the zone whose reference point is nearest the location's coordinates | 3,992 |
| A country with several zones and no coordinates for the location: left out | 1,620 |
| A country `zone.tab` does not list: left out | 26 |
| A code the release lists twice: the first entry kept | 74 |

**Limits.**
- **Nearest reference point is an approximation.** Near a zone boundary in a large country (the United States,
  Russia, Brazil, Australia, Canada, Mexico, Indonesia), a port can get the neighbouring zone. Those 3,992 rows are
  the ones to check before a time there matters to money.
- **Germany's exclave zone is not used.** `zone.tab` also lists Europe/Busingen, whose reference point would be
  nearest for all of south-west Germany. The corridor's countries therefore take their principal zone outright, and
  Büsingen itself gets Europe/Berlin, which has had the same offsets since 1980.
- **Some major ports are missing.** Their entries in a multi-zone country carry no coordinates: Vancouver
  (`CAVAN`) and Vladivostok (`RUVVO`) among them. A port the business needs is added by a reviewed migration with
  its zone stated.

## A new release

A new release is a new migration, never an edit of V19:
1. Download the release from UNECE into `local/unlocode/` (gitignored).
2. Take `zone.tab` from a current tzdata.
3. Run:

   ```
   python scripts/build_unlocode_migration.py --release <release> --tzdata <tzdata> \
       --source local/unlocode --zones local/unlocode/zone.tab \
       --out logistics-service/src/main/resources/db/migration/V<n>__unlocode_<release>.sql
   ```
4. Review the counts in its header against the previous release's.

The generator upserts, so codes that keep their names and zones are unchanged. A code that disappears from a
release stays in the table: shipments may name it.

## Terms

The brief requires UNECE's terms for redistributing UN/LOCODE to be checked before a subset is committed. They
could not be confirmed on 2026-11-26: unece.org refuses automated requests and shows a bot check in the browser.
UNECE's Recommendation 16, the standard that UN/LOCODE implements, disclaims liability for use of the code list but
states no licence. This section is to be completed with the terms, quoted from UNECE's site, before V19 is
published.
