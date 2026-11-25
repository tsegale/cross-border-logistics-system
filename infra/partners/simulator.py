"""Stand-ins for the outside services FreightFlow talks to, so CI and the fire drill never call a live API.

ECB (ADR 0034): GET /ecb/service/data/EXR/D.<currency>.EUR.SP00.A?lastNObservations=N&format=csvdata
  answers like the ECB Data Portal: the CSV header and row layout recorded from it on 2026-11-24, one row per TARGET
  working day whose rate has been published (16:00 UTC, after the ECB's 16:00 CET), the rates themselves the five
  recorded from the ECB for 17 to 23 November 2026, repeated. Only ZAR is served; any other series is the ECB's 404.
Bank of Namibia: GET /bon/ratestable/api/rates
  the shape of the bank's own rates table, its euro rate the simulated ECB rate of the same day times 1.0016 (the
  difference measured between the two sources on 23 November 2026).
Control: POST /_control with a JSON object of any of
  ecbStatus (an HTTP status the ECB answers with instead, 200 for normal), ecbLagWorkingDays (the newest observation
  is this many working days old), bonStatus, bonFactor (Bank of Namibia rate = ECB rate times this).
  GET /_control shows the current settings. Environment variables of the same names in upper snake case set them at
  start. Nothing here is persisted.
Carrier (ADR 0037): carrier.py, under /carrier and /_control/carrier.
GET /health answers 200.

HTTPS only (ADR 0040), TLS 1.2 or 1.3 with the certificate the agent issues to partner-simulator (TLS_CERT, TLS_KEY),
re-read every minute when the agent has renewed it: new connections get the new one.
"""

import datetime
import json
import os
import ssl
import sys
import threading
import time
from decimal import Decimal, ROUND_HALF_EVEN
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

# carrier.py sits next to this file; python -I leaves the script's directory off the path.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import carrier  # noqa: E402

RECORDED_ZAR = [Decimal("18.6073"), Decimal("18.8897"), Decimal("18.9948"), Decimal("19.1987"), Decimal("19.1513")]
ECB_HEADER = "KEY,FREQ,CURRENCY,CURRENCY_DENOM,EXR_TYPE,EXR_SUFFIX,TIME_PERIOD,OBS_VALUE"
PUBLISHED_AT_UTC = datetime.time(16, 0)
MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

settings_lock = threading.Lock()
settings = {
    "ecbStatus": int(os.environ.get("ECB_STATUS", "200")),
    "ecbLagWorkingDays": int(os.environ.get("ECB_LAG_WORKING_DAYS", "0")),
    "bonStatus": int(os.environ.get("BON_STATUS", "200")),
    "bonFactor": os.environ.get("BON_FACTOR", "1.0016"),
}


def easter_sunday(year):
    a, b, c = year % 19, year // 100, year % 100
    d, e = b // 4, b % 4
    f = (b + 8) // 25
    g = (b - f + 1) // 3
    h = (19 * a + b - d - g + 15) % 30
    i, k = c // 4, c % 4
    l = (32 + 2 * e + 2 * i - h - k) % 7
    m = (a + 11 * h + 22 * l) // 451
    month = (h + l - 7 * m + 114) // 31
    day = (h + l - 7 * m + 114) % 31 + 1
    return datetime.date(year, month, day)


def is_target_working_day(day):
    """Saturdays, Sundays, 1 January, Good Friday, Easter Monday, 1 May, 25 and 26 December are TARGET closing days."""
    if day.weekday() >= 5 or (day.month, day.day) in {(1, 1), (5, 1), (12, 25), (12, 26)}:
        return False
    easter = easter_sunday(day.year)
    return day not in (easter - datetime.timedelta(days=2), easter + datetime.timedelta(days=1))


def published_days(count, lag):
    """The newest `count` working days with a published rate, oldest first, `lag` working days back."""
    now = datetime.datetime.now(datetime.timezone.utc)
    day = now.date() if now.time() >= PUBLISHED_AT_UTC else now.date() - datetime.timedelta(days=1)
    days = []
    while len(days) < count + lag:
        if is_target_working_day(day):
            days.append(day)
        day -= datetime.timedelta(days=1)
    return list(reversed(days[lag:]))


def zar_rate(day):
    return RECORDED_ZAR[day.toordinal() % len(RECORDED_ZAR)]


class Handler(BaseHTTPRequestHandler):
    server_version = "partner-simulator"

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/health":
            return self.answer(200, "text/plain", "ok")
        if url.path == "/_control":
            with settings_lock:
                return self.answer(200, "application/json", json.dumps(settings))
        if url.path.startswith("/ecb/service/data/EXR/"):
            return self.ecb(url)
        if url.path == "/bon/ratestable/api/rates":
            return self.bon()
        if url.path == "/carrier/v2/events":
            return self.carrier_events(url)
        if url.path == "/_control/carrier":
            return self.answer(200, "application/json", json.dumps(carrier.control({})))
        return self.answer(404, "text/plain", "not simulated")

    def do_POST(self):
        path = urlparse(self.path).path
        length = int(self.headers.get("Content-Length", "0"))
        try:
            change = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError as error:
            return self.answer(400, "text/plain", f"not JSON: {error}")
        if path == "/_control/carrier/voyages":
            if not isinstance(change, dict) or "containerNumber" not in change:
                return self.answer(400, "text/plain", "containerNumber is required")
            return self.answer(202, "application/json", json.dumps(carrier.start_voyage(change)))
        if path == "/_control/carrier/forge":
            return self.answer(200, "application/json", json.dumps(carrier.forge()))
        if path == "/_control/carrier/replay":
            return self.answer(200, "application/json", json.dumps(carrier.replay()))
        if path == "/_control/carrier":
            return self.answer(200, "application/json", json.dumps(carrier.control(change)))
        if path != "/_control":
            return self.answer(404, "text/plain", "not simulated")
        unknown = set(change) - set(settings)
        if unknown:
            return self.answer(400, "text/plain", f"unknown settings: {sorted(unknown)}")
        with settings_lock:
            settings.update(change)
            return self.answer(200, "application/json", json.dumps(settings))

    def ecb(self, url):
        with settings_lock:
            status, lag = int(settings["ecbStatus"]), int(settings["ecbLagWorkingDays"])
        if status != 200:
            return self.answer(status, "text/plain", "simulated ECB failure")
        series = url.path.rsplit("/", 1)[-1]
        parts = series.split(".")
        if len(parts) != 5 or parts[1] != "ZAR" or parts[2] != "EUR":
            detail = f"No Series was returned for the query: {series}"
            return self.answer(404, "application/json", json.dumps({"detail": detail, "status": 404,
                                                                      "title": "Not Found"}))
        query = parse_qs(url.query)
        count = max(1, min(int(query.get("lastNObservations", ["5"])[0]), 100))
        rows = [ECB_HEADER] + [f"EXR.D.ZAR.EUR.SP00.A,D,ZAR,EUR,SP00,A,{day.isoformat()},{zar_rate(day)}"
                               for day in published_days(count, lag)]
        return self.answer(200, "text/csv", "\r\n".join(rows) + "\r\n")

    def bon(self):
        with settings_lock:
            status, lag, factor = int(settings["bonStatus"]), int(settings["ecbLagWorkingDays"]), settings["bonFactor"]
        if status != 200:
            return self.answer(status, "text/plain", "simulated Bank of Namibia failure")
        day = published_days(1, lag)[-1]
        euro = (zar_rate(day) * Decimal(str(factor))).quantize(Decimal("0.0001"), rounding=ROUND_HALF_EVEN)
        date = f"{day.day} {MONTHS[day.month - 1]} {day.year}"
        table = {"rateDollar": "17.4623", "rateDollarDate": date, "rateEuro": str(euro), "rateEuroDate": date,
                 "rateAng": "0.0189", "rateAngDate": date, "ratePound": "22.8978", "ratePoundDate": date}
        return self.answer(200, "application/json", json.dumps(table))

    def carrier_events(self, url):
        query = parse_qs(url.query)
        after = query.get("eventCreatedDateTime:gte", [None])[0]
        try:
            created_after = datetime.datetime.fromisoformat(after) if after else None
        except ValueError:
            return self.answer(400, "text/plain", "eventCreatedDateTime:gte must be ISO 8601")
        status, events = carrier.page(created_after, max(1, min(int(query.get("limit", ["100"])[0]), 100)))
        if status != 200:
            return self.answer(status, "text/plain", "simulated carrier failure")
        return self.answer(200, "application/json", json.dumps(events))

    def answer(self, status, content_type, body):
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format, *args):
        # One line per request on stdout, without the client address.
        print(f"{self.command} {self.path} -> {args[1] if len(args) > 1 else ''}", flush=True)


def reload_on_renewal(context: ssl.SSLContext, cert: str, key: str) -> None:
    """Loads the renewed certificate into the live context; handshakes after it present the new one."""
    loaded = os.stat(cert).st_mtime_ns
    while True:
        time.sleep(int(os.environ.get("TLS_CHECK_SECONDS", "60")))
        try:
            changed = os.stat(cert).st_mtime_ns
            if changed != loaded:
                context.load_cert_chain(cert, key)
                loaded = changed
                print("partner-simulator tls: certificate renewed, reloaded", flush=True)
        except (OSError, ssl.SSLError) as error:
            print(f"partner-simulator tls: reload failed, retrying: {error}", flush=True)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8090"))
    cert = os.environ.get("TLS_CERT", "/tls/cert.pem")
    key = os.environ.get("TLS_KEY", "/tls/key.pem")
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(cert, key)
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    # The handshake in each request's own thread, not in the accept loop, where one slow client would hold up all.
    server.socket = context.wrap_socket(server.socket, server_side=True, do_handshake_on_connect=False)
    threading.Thread(target=reload_on_renewal, args=(context, cert, key), daemon=True).start()
    print(f"partner-simulator on :{port} (TLS)", flush=True)
    server.serve_forever()
