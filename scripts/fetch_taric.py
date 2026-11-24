#!/usr/bin/env python3
"""Fetches the EU TARIC measures for the goods codes FreightFlow quotes (ADR 0035) and prints them as JSON, to review
before they become a migration. TARIC has no public API: this reads the same files its consultation pages load,
the nomenclature tree (nomenclaturetree_<lang>_<date>_<chapter>_<lang>.js) for each code's description and the
measure details page (measures_details.jsp) for its duties, for imports from one origin country on one date.

    python3 scripts/fetch_taric.py --date 2026-11-24 --origin NA 0304741100 0804100000 > taric.json

Per code it reports the description path, the third-country duty (measure type 103, erga omnes), any tariff
preference that names a group the origin belongs to (type 142), and every other measure line as found, so a reviewer
sees what the subset leaves out (quotas, controls, entry prices). Nothing is written to the database: a reviewed
migration does that (logistics-service/src/main/resources/db/migration).
"""
from __future__ import annotations

import argparse
import html
import json
import re
import sys
import urllib.request

BASE = "https://ec.europa.eu/taxation_customs/dds2/taric/"
AGENT = {"User-Agent": "Mozilla/5.0 (FreightFlow tariff subset fetcher)"}


def get(url: str) -> str:
    with urllib.request.urlopen(urllib.request.Request(url, headers=AGENT), timeout=60) as response:
        return response.read().decode("utf-8", errors="replace")


def text(page: str) -> str:
    page = re.sub(r"<script.*?</script>|<style.*?</style>", " ", page, flags=re.S)
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", page))).strip()


def descriptions(code: str, date: str) -> list[str]:
    """The description of the code and of every heading above it, from the chapter's nomenclature tree."""
    tree = get(f"{BASE}nomenclaturetree/nomenclaturetree_en_{date}_{code[:2]}_en.js")
    path: list[str] = []
    # Entries look like ["false","<level>","<code>","<suffix>","<indent>","<description>",...
    for match in re.finditer(r'\["(?:true|false)","(\d+)","(\d{10})","(\d{2})","(\d+)","((?:[^"\\]|\\.)*)"', tree):
        level, entry, suffix, indent, description = match.groups()
        if code.startswith(entry.rstrip("0")) or entry == code:
            depth = int(indent)
            path = path[:depth]
            path.append(description.rstrip(" :"))
        if entry == code and suffix == "80":
            return path
    return path


def measures(code: str, origin: str, date: str) -> list[str]:
    compact = date.replace("-", "")
    page = get(f"{BASE}measures.jsp?Lang=en&Taric={code}&Area={origin}&SimDate={compact}&Level=1&Indent=-1"
               f"&LangDescr=en&GoodsText=&Domain=TARIC")
    details = re.search(r"measures_details\.jsp\?[^\"' ]+", page)
    if not details:
        raise SystemExit(f"{code}: no measure details link on the TARIC page")
    body = text(get(BASE + html.unescape(details.group(0))))
    start = body.find("Additional Code:")
    body = body[start:] if start >= 0 else body
    body = body.split(" Top ")[0]
    # One line per measure: "<description> (<start> - <end>) : <duty> <legal base>".
    return [line.strip() for line in re.split(r"\s(?=\b[A-Z][A-Za-z ,'-]+ \(\d{2}-\d{2}-\d{4} - )", body) if line.strip()]


def duty(lines: list[str], name: str, body: str) -> dict | None:
    """The measure's validity, its rate as TARIC writes it (ad valorem, or ad valorem plus a specific amount) and its
    legal base, the regulation or decision TARIC cites."""
    for line in lines:
        found = re.match(re.escape(name) + r" \((\d{2}-\d{2}-\d{4}) - (\d{2}-\d{2}-\d{4})?\) : (.+?) \d+ [RD]\d{7} "
                         r"([RD]\d{4}/\d{2})", line)
        if found:
            return {"from": found.group(1), "to": found.group(2), "rate": found.group(3).strip(),
                    "legalBase": found.group(4)}
    return None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--date", required=True, help="reference date, YYYY-MM-DD")
    parser.add_argument("--origin", required=True, help="ISO 3166 origin country, e.g. NA")
    parser.add_argument("codes", nargs="+", help="10-digit TARIC codes")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    compact = args.date.replace("-", "")
    result = {"source": "EU TARIC consultation, " + BASE, "referenceDate": args.date, "origin": args.origin,
              "codes": []}
    for code in args.codes:
        lines = measures(code, args.origin, args.date)
        result["codes"].append({
            "code": code,
            "description": descriptions(code, compact),
            "thirdCountryDuty": duty(lines, "Third country duty", ""),
            "tariffPreference": duty(lines, "Tariff preference", ""),
            "sadcEpa": any("SADC EPA" in line for line in lines),
            "measures": lines,
        })
        print(f"{code}: {len(lines)} measure lines", file=sys.stderr)
    json.dump(result, sys.stdout, indent=2, ensure_ascii=False)
    print()


if __name__ == "__main__":
    main()
