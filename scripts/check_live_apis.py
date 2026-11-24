#!/usr/bin/env python3
"""Checks that the outside APIs FreightFlow depends on still answer the way its recorded fixtures say (ADR 0034,
ADR 0035, ADR 0037). Run on a schedule by .github/workflows/live-apis.yml, never by a CI build: no build depends on a
live API. Prints one line per check and exits 1 if any differs, so the workflow can open an issue.

    python3 scripts/check_live_apis.py [--json report.json]

Checks
  ecb       the ECB Data Portal's ZAR series as CSV: the header the parser reads by name (EcbRates), a recent value
  bon       the Bank of Namibia's rates table: rateEuro and rateEuroDate as BonRates reads them
  taric     a TARIC measure page for 0304 74 11 00 from Namibia: the third-country duty and the SADC EPA preference
            the V13 subset holds (7.50 %, 0 %)
  dcsa      the public DCSA T&T 2.2.0 document still served, with the callback signature header the webhook checks
"""
from __future__ import annotations

import argparse
import csv
import html
import io
import json
import re
import sys
import urllib.request
from datetime import date, datetime, timedelta, timezone

AGENT = {"User-Agent": "FreightFlow live API check (scheduled, weekly)"}


def get(url: str, accept: str = "*/*") -> str:
    request = urllib.request.Request(url, headers={**AGENT, "Accept": accept})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8", errors="replace")


def check_ecb() -> str:
    body = get("https://data-api.ecb.europa.eu/service/data/EXR/D.ZAR.EUR.SP00.A"
               "?lastNObservations=3&format=csvdata&detail=dataonly", "text/csv")
    rows = list(csv.DictReader(io.StringIO(body)))
    for column in ("CURRENCY", "CURRENCY_DENOM", "TIME_PERIOD", "OBS_VALUE"):
        if not rows or column not in rows[0]:
            raise AssertionError(f"column {column} missing from the CSV header")
    newest = date.fromisoformat(rows[-1]["TIME_PERIOD"])
    if newest < date.today() - timedelta(days=7):
        raise AssertionError(f"newest observation {newest} is over a week old")
    float(rows[-1]["OBS_VALUE"])
    return f"{len(rows)} rows, newest {newest} = {rows[-1]['OBS_VALUE']}"


def check_bon() -> str:
    table = json.loads(get("https://services.bon.com.na/ratestable/api/rates", "application/json"))
    rate, day = table.get("rateEuro"), table.get("rateEuroDate")
    if not rate or not day:
        raise AssertionError("rateEuro or rateEuroDate missing: the table's shape changed")
    datetime.strptime(day.strip(), "%d %b %Y")
    float(rate)
    return f"rateEuro {rate} for {day}"


def check_taric() -> str:
    simdate = datetime.now(timezone.utc).strftime("%Y%m%d")
    base = "https://ec.europa.eu/taxation_customs/dds2/taric/"
    page = get(f"{base}measures.jsp?Lang=en&Taric=0304741100&Area=NA&SimDate={simdate}&Level=1&Indent=-1"
               "&LangDescr=en&GoodsText=&Domain=TARIC")
    link = re.search(r"measures_details\.jsp\?[^\"' ]+", page)
    if not link:
        raise AssertionError("no measure details link: the consultation pages changed")
    details = html.unescape(re.sub(r"<[^>]+>", " ", get(base + html.unescape(link.group(0)))))
    details = re.sub(r"\s+", " ", details.replace(" ", " "))
    if not re.search(r"Third country duty \([0-9-]+ - \) : 7\.50 %", details):
        raise AssertionError("third-country duty for 0304 74 11 00 is no longer 7.50 %: review the V13 subset")
    if not re.search(r"Tariff preference \([0-9-]+ - \) : 0 %", details):
        raise AssertionError("SADC EPA preference for 0304 74 11 00 is no longer 0 %: review the V13 subset")
    return "0304 74 11 00 from NA: third country 7.50 %, SADC EPA 0 %"


def check_dcsa() -> str:
    spec = json.loads(get("https://api.swaggerhub.com/apis/dcsaorg/DCSA_TNT/2.2.0?resolved=true", "application/json"))
    if spec.get("info", {}).get("version") != "2.2.0":
        raise AssertionError(f"version {spec.get('info', {}).get('version')}")
    if "Notification-Signature" not in json.dumps(spec):
        raise AssertionError("the callback no longer documents Notification-Signature")
    return "T&T 2.2.0 served, Notification-Signature documented"


CHECKS = {"ecb": check_ecb, "bon": check_bon, "taric": check_taric, "dcsa": check_dcsa}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--json", help="write the results here")
    args = parser.parse_args()
    results = {}
    for name, check in CHECKS.items():
        try:
            results[name] = {"ok": True, "detail": check()}
        except Exception as error:  # noqa: BLE001 - every failure, of any kind, is a finding to report
            results[name] = {"ok": False, "detail": f"{type(error).__name__}: {error}"}
        print(f"{'ok  ' if results[name]['ok'] else 'FAIL'} {name}: {results[name]['detail']}", flush=True)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(results, handle, indent=2)
    if not all(result["ok"] for result in results.values()):
        sys.exit(1)


if __name__ == "__main__":
    main()
