"""ZAP's API scan of the public edge, as an attacker sees it (ADR 0042).

Imports the OpenAPI documents banking and logistics publish through the gateway, then runs ZAP's API scan (passive
rules, then an active scan capped at a few minutes) against https://api-gateway:8080 from inside the compose network,
with a customer's bearer token: the role an outside account has. Each API gets a fresh token, since the realm's last
5 minutes. Rules are judged by infra/zap/rules.tsv, the reviewed baseline: a rule marked FAIL that fires fails the
scan, IGNORE is a reviewed false positive or accepted risk, with the reason beside it.

    python scripts/zap_scan.py --out zap            # scan; exit 1 if a FAIL rule fired
    python scripts/zap_scan.py --out zap --generate # also write each API's full rule list (gen-<api>.conf)

Needs the default stack running and Docker; nothing on the host beyond Python.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from internal_ca import ssl_context  # noqa: E402
from keycloak_token import PEOPLE, read_env_file, user_token  # noqa: E402

REPOSITORY = Path(__file__).resolve().parent.parent
GATEWAY = os.environ.get("GATEWAY_URL", "https://127.0.0.1:8080")
# Inside the network: the name the gateway's certificate carries.
TARGET = "https://api-gateway:8080"
# ZAP 2.17.0 (github.com/zaproxy/zaproxy/releases, checked 2026-11-26), by digest.
IMAGE = "zaproxy/zap-stable:2.17.0@sha256:9624669db72f54d86c4b46a7064518bd9bf79d8c61a4c31befa9923489f328af"
APIS = {"banking": "/docs/banking/v3/api-docs", "logistics": "/docs/logistics/v3/api-docs"}
PERSONA = "customer-namib-exports"
ACTIVE_SCAN_MINUTES = 4


def fetch_document(path: str, token: str) -> dict:
    request = urllib.request.Request(GATEWAY + path, headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(request, timeout=30, context=ssl_context()) as response:
        document = json.load(response)
    # The services describe themselves; the scan goes through the edge, under its name.
    document["servers"] = [{"url": TARGET}]
    return document


def scan(api: str, out: Path, generate: bool) -> int:
    token = user_token(PERSONA, read_env_file()[PEOPLE[PERSONA]])
    (out / f"{api}-openapi.json").write_text(json.dumps(document := fetch_document(APIS[api], token)))
    command = [
        "docker", "run", "--rm", "--network", "freightflow_default",
        "-v", f"{out.resolve()}:/zap/wrk:rw",
        # The bearer token on every request ZAP sends to the gateway, and nowhere else.
        "-e", f"ZAP_AUTH_HEADER_VALUE=Bearer {token}", "-e", "ZAP_AUTH_HEADER_SITE=api-gateway",
        IMAGE, "zap-api-scan.py", "-t", f"/zap/wrk/{api}-openapi.json", "-f", "openapi",
        "-c", "rules.tsv", "-J", f"{api}.json", "-r", f"{api}.html", "-w", f"{api}.md",
        # Warnings are reported, not failed: only a rule the baseline marks FAIL fails the build.
        "-I",
        "-z", f"-config scanner.maxScanDurationInMins={ACTIVE_SCAN_MINUTES} -config scanner.maxRuleDurationInMins=1",
    ]
    if generate:
        command += ["-g", f"gen-{api}.conf"]
    print(f"zap: {api}, {len(document.get('paths', {}))} paths", flush=True)
    result = subprocess.run(command, timeout=1800)
    # zap-api-scan: 0 clean, 1 a FAIL rule fired, 2 warnings only (not with -I), 3 the scan itself failed.
    print(f"zap: {api} finished with {result.returncode}", flush=True)
    return result.returncode


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", default="zap", help="reports go here")
    parser.add_argument("--generate", action="store_true", help="also write the full rule list per API")
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    # ZAP runs as its own user (uid 1000) and writes its reports here.
    out.chmod(0o777)
    shutil.copy(REPOSITORY / "infra" / "zap" / "rules.tsv", out / "rules.tsv")
    codes = {api: scan(api, out, args.generate) for api in APIS}
    sys.exit(1 if any(code in (1, 3) for code in codes.values()) else 0)


if __name__ == "__main__":
    main()
