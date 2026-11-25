"""A carrier that speaks DCSA Track and Trace 2.2 (ADR 0037), so the demo and CI need no carrier account.

POST /_control/carrier/voyages  {"containerNumber": "...", "origin": "NAWVB", "destination": "DEHAM",
                                 "intervalSeconds": 2, "mode": "push", "carrierBookingReference": "...",
                                 "vesselIMONumber": "9321483", "voyageNumber": "2647N"}
  runs the container through a voyage: PICK at the origin, GTIN, LOAD, the vessel's DEPA, its ARRI at the
  destination, DISC, GTOT and DROP, one event every intervalSeconds. Each event is an actual (ACT) DCSA event with a
  fresh eventID. LOAD, DISC, DEPA and ARRI name the vessel and voyage in their transportCall, and every event names
  the booking (documentReferences BKG) when carrierBookingReference is given: that is how a transport event, which
  names no container, finds its shipments. mode push sends each as a notification to CARRIER_CALLBACK_URL with Subscription-ID, API-Version and
  Notification-Signature (HMAC-SHA256 over the body, keyed with the Base64 secret in CARRIER_SECRET_FILE); mode poll
  only keeps them for GET /carrier/v2/events.
GET /carrier/v2/events?eventCreatedDateTime:gte=<ISO 8601>&limit=<n>
  the events created so far, oldest first, as T&T 2.2 answers.
POST /_control/carrier/forge     sends one notification signed with the wrong key (the fire drill's forgery).
POST /_control/carrier/replay    sends the first notification ever sent again, with its original signature.
POST /_control/carrier           {"pollStatus": 503} makes GET /carrier/v2/events fail; 200 again for normal.
"""

import base64
import datetime
import hashlib
import hmac
import json
import os
import ssl
import threading
import time
import urllib.error
import urllib.request
import uuid

SUBSCRIPTION_ID = os.environ.get("CARRIER_SUBSCRIPTION_ID", "6f2d0a6e-1c1d-4f53-9a64-1c4e8f0b7a21")
CALLBACK_URL = os.environ.get("CARRIER_CALLBACK_URL", "http://logistics-service:8082/api/v1/dcsa/notifications/"
                                                      "carrier-kalahari-freight")
SECRET_FILE = os.environ.get("CARRIER_SECRET_FILE", "/run/secrets/dcsa_carrier_secret")
# The gateway is HTTPS with a certificate from the internal CA (ADR 0039): verified against its root, by name.
CA_FILE = os.environ.get("CARRIER_CA_FILE", "/tls/ca.pem")

lock = threading.Lock()
events = []
sent = []
state = {"pollStatus": 200, "notificationsSent": 0, "notificationsRefused": 0}


def now():
    return datetime.datetime.now(datetime.timezone.utc)


def iso(moment):
    return moment.isoformat(timespec="milliseconds")


def secret():
    with open(SECRET_FILE, encoding="utf-8") as handle:
        return base64.b64decode(handle.read().strip())


def sign(body, key):
    return "sha256=" + hmac.new(key, body, hashlib.sha256).hexdigest()


def vessel_call(location, ship):
    """A transport call on the vessel: its IMO number and the voyage, as LOAD, DISC, DEPA and ARRI carry them."""
    return {"transportCallID": str(uuid.uuid4()), "carrierServiceCode": "NAE",
            "exportVoyageNumber": ship["voyage"], "importVoyageNumber": ship["voyage"], "UNLocationCode": location,
            "modeOfTransport": "VESSEL", "vessel": {"vesselIMONumber": ship["imo"], "vesselName": "Kalahari Express"}}


def with_booking(event, booking):
    if booking:
        event["documentReferences"] = [{"documentReferenceType": "BKG", "documentReferenceValue": booking}]
    return event


def equipment_event(code, container, location, ship=None, booking=None):
    call = vessel_call(location, ship) if ship and code in ("LOAD", "DISC") else {
        "transportCallID": str(uuid.uuid4()), "UNLocationCode": location,
        "modeOfTransport": "VESSEL" if code in ("LOAD", "DISC") else "TRUCK"}
    return with_booking({"eventID": str(uuid.uuid4()), "eventCreatedDateTime": iso(now()), "eventType": "EQUIPMENT",
                         "eventClassifierCode": "ACT", "eventDateTime": iso(now()), "equipmentEventTypeCode": code,
                         "equipmentReference": container, "ISOEquipmentCode": "22RT", "emptyIndicatorCode": "LADEN",
                         "transportCall": call}, booking)


def transport_event(code, location, ship, booking=None):
    return with_booking({"eventID": str(uuid.uuid4()), "eventCreatedDateTime": iso(now()), "eventType": "TRANSPORT",
                         "eventClassifierCode": "ACT", "eventDateTime": iso(now()), "transportEventTypeCode": code,
                         "transportCall": vessel_call(location, ship)}, booking)


def voyage(container, origin, destination, ship, booking):
    def equipment(code, location):
        return lambda: equipment_event(code, container, location, ship, booking)

    def transport(code, location):
        return lambda: transport_event(code, location, ship, booking)

    return [equipment("PICK", origin), equipment("GTIN", origin), equipment("LOAD", origin),
            transport("DEPA", origin), transport("ARRI", destination), equipment("DISC", destination),
            equipment("GTOT", destination), equipment("DROP", destination)]


def push(body, signature):
    request = urllib.request.Request(CALLBACK_URL, data=body, method="POST", headers={
        "Content-Type": "application/json", "Subscription-ID": SUBSCRIPTION_ID, "API-Version": "2.2.0",
        "Notification-Signature": signature})
    try:
        # Read for each push: the root lives as long as the stack's CA, and may appear after this process starts.
        context = ssl.create_default_context(cafile=CA_FILE) if CALLBACK_URL.startswith("https:") else None
        with urllib.request.urlopen(request, timeout=10, context=context) as response:
            status = response.status
    except urllib.error.HTTPError as error:
        status = error.code
    except OSError as error:
        print(f"carrier: notification not delivered: {error}", flush=True)
        status = 0
    with lock:
        state["notificationsSent" if status == 204 else "notificationsRefused"] += 1
    print(f"carrier: notification -> {status}", flush=True)
    return status


def run(container, origin, destination, interval, mode, ship, booking):
    for make in voyage(container, origin, destination, ship, booking):
        event = make()
        with lock:
            events.append(event)
        if mode == "push":
            body = json.dumps([event]).encode("utf-8")
            signature = sign(body, secret())
            with lock:
                sent.append((body, signature))
            push(body, signature)
        time.sleep(interval)


def start_voyage(spec):
    container = spec["containerNumber"]
    ship = {"imo": spec.get("vesselIMONumber", "9321483"), "voyage": spec.get("voyageNumber", "2647N")}
    thread = threading.Thread(target=run, daemon=True, args=(container, spec.get("origin", "NAWVB"),
                                                            spec.get("destination", "DEHAM"),
                                                            float(spec.get("intervalSeconds", 2)),
                                                            spec.get("mode", "push"), ship,
                                                            spec.get("carrierBookingReference")))
    thread.start()
    return {"containerNumber": container, "events": 8}


def forge():
    body = json.dumps([equipment_event("GTIN", "CSQU3054383", "NAWVB")]).encode("utf-8")
    return {"status": push(body, sign(body, b"not-the-shared-secret-at-all-000"))}


def replay():
    with lock:
        if not sent:
            return {"status": None, "detail": "nothing sent yet"}
        body, signature = sent[0]
    return {"status": push(body, signature)}


def page(created_after, limit):
    with lock:
        if state["pollStatus"] != 200:
            return state["pollStatus"], None
        found = [event for event in events if created_after is None
                 or datetime.datetime.fromisoformat(event["eventCreatedDateTime"]) >= created_after]
    return 200, found[:limit]


def control(change):
    with lock:
        if "pollStatus" in change:
            state["pollStatus"] = int(change["pollStatus"])
        return dict(state, eventsKept=len(events))
