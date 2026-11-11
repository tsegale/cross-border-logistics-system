#!/usr/bin/env python3
"""Get a real Keycloak access token for a demo persona (ADR 0016). Used by scripts/token.sh, seed_demo.sh and
smoke_test.sh.

    python scripts/keycloak_token.py customer-namib-exports      # people: authorization code + PKCE
    python scripts/keycloak_token.py carrier-walvis-haulage      # machine clients: client credentials
    python scripts/keycloak_token.py operator --claims           # the decoded claims instead of the token

People log in exactly as a browser would: the public client's authorization code flow with PKCE, submitting Keycloak's
own login form. The realm has no password grant to take a shortcut through. Credentials come from secrets/
(scripts/generate_secrets.py). Standard library only.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import html
import http.cookiejar
import json
import os
import re
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_secrets import read_env_file, read_secret  # noqa: E402

BASE_URL = os.environ.get("KEYCLOAK_URL", "http://127.0.0.1:8180")
REALM_URL = f"{BASE_URL}/realms/freightflow/protocol/openid-connect"
PUBLIC_CLIENT = "freightflow-web"
REDIRECT_URI = os.environ.get("KEYCLOAK_WEB_ORIGIN", "http://127.0.0.1:3000") + "/callback"
PEOPLE = {
    "customer-namib-exports": "KEYCLOAK_PASSWORD_CUSTOMER_NAMIB_EXPORTS",
    "customer-hanseatic-imports": "KEYCLOAK_PASSWORD_CUSTOMER_HANSEATIC_IMPORTS",
    "operator": "KEYCLOAK_PASSWORD_OPERATOR",
    "auditor": "KEYCLOAK_PASSWORD_AUDITOR",
}
MACHINES = {
    "carrier-walvis-haulage": lambda: read_env_file()["KEYCLOAK_CARRIER_WALVIS_HAULAGE_SECRET"],
    "logistics-service": lambda: read_secret("logistics_client_secret"),
}


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """The login's last redirect goes to the front end, which isn't running: the code is read from it instead."""

    def redirect_request(self, *args, **kwargs):
        return None


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _token_request(form: dict[str, str]) -> str:
    request = urllib.request.Request(f"{REALM_URL}/token", data=urllib.parse.urlencode(form).encode())
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)["access_token"]
    except urllib.error.HTTPError as error:
        raise SystemExit(f"token request failed: {error.code} {error.read().decode(errors='replace')}") from error


def user_token(username: str, password: str) -> str:
    verifier = _b64url(secrets.token_bytes(48))
    query = urllib.parse.urlencode({
        "client_id": PUBLIC_CLIENT,
        "response_type": "code",
        "scope": "openid",
        "redirect_uri": REDIRECT_URI,
        "state": secrets.token_urlsafe(16),
        "code_challenge": _b64url(hashlib.sha256(verifier.encode()).digest()),
        "code_challenge_method": "S256",
    })
    # Keycloak marks its login cookies Secure. Browsers send those to a local host over plain HTTP; cookiejar has to
    # be told to, and only for a local Keycloak: anywhere else that URL is HTTPS anyway.
    local = urllib.parse.urlparse(BASE_URL).hostname in ("localhost", "127.0.0.1")
    policy = http.cookiejar.DefaultCookiePolicy(secure_protocols=("https", "wss", "http") if local else ("https", "wss"))
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar(policy)), _NoRedirect())
    with opener.open(f"{REALM_URL}/auth?{query}", timeout=30) as response:
        page = response.read().decode()
    form_action = re.search(r'<form[^>]*action="([^"]+)"', page)
    if not form_action:
        raise SystemExit("no login form in Keycloak's answer: is the client configured for this redirect URI?")
    # With organizations enabled the browser flow is identifier-first: the username, then a page asking only for the
    # password. Submitting both at once also works on a plain username-and-password form.
    form = {"username": username, "password": password, "credentialId": ""}
    action = html.unescape(form_action.group(1))
    location = None
    for _ in range(3):
        try:
            with opener.open(action, data=urllib.parse.urlencode(form).encode(), timeout=30) as response:
                page = response.read().decode()
        except urllib.error.HTTPError as redirect:
            if redirect.code != 302:
                raise SystemExit(f"login failed for {username}: HTTP {redirect.code}") from redirect
            location = redirect.headers["Location"]
            break
        next_action = re.search(r'<form[^>]*action="([^"]+)"', page)
        if not next_action or 'name="password"' not in page:
            break
        action = html.unescape(next_action.group(1))
        form = {"password": password, "credentialId": ""}
    if location is None:
        raise SystemExit(f"login failed for {username}: wrong password, or the account is temporarily locked")
    code = urllib.parse.parse_qs(urllib.parse.urlparse(location).query).get("code")
    if not code:
        raise SystemExit(f"no code in the login's redirect for {username}")
    return _token_request({
        "grant_type": "authorization_code",
        "client_id": PUBLIC_CLIENT,
        "code": code[0],
        "redirect_uri": REDIRECT_URI,
        "code_verifier": verifier,
    })


def client_token(client_id: str, secret: str) -> str:
    return _token_request({"grant_type": "client_credentials", "client_id": client_id, "client_secret": secret})


def wait_until_serving(timeout_s: float = 300) -> None:
    """Keycloak reports healthy before its realm import finishes and answers 503 until then (ADR 0016)."""
    deadline = time.time() + timeout_s
    url = f"{BASE_URL}/realms/freightflow/.well-known/openid-configuration"
    while True:
        try:
            with urllib.request.urlopen(url, timeout=10):
                return
        except (urllib.error.URLError, ConnectionError, TimeoutError) as error:
            if time.time() > deadline:
                raise SystemExit(f"Keycloak at {BASE_URL} is not serving the freightflow realm: {error}") from error
            time.sleep(3)


def token_for(persona: str) -> str:
    wait_until_serving()
    try:
        if persona in PEOPLE:
            return user_token(persona, read_env_file()[PEOPLE[persona]])
        return client_token(persona, MACHINES[persona]())
    except (KeyError, FileNotFoundError) as missing:
        raise SystemExit(f"{missing} not found: run python scripts/generate_secrets.py first") from missing


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("persona", choices=[*PEOPLE, *MACHINES])
    parser.add_argument("--claims", action="store_true", help="print the decoded claims, not the token")
    args = parser.parse_args()
    token = token_for(args.persona)
    if args.claims:
        payload = token.split(".")[1]
        print(json.dumps(json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))), indent=2))
    else:
        print(token)


if __name__ == "__main__":
    main()
