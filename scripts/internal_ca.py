#!/usr/bin/env python3
"""The internal CA's root on the host (ADR 0039), for the scripts that call the stack's TLS endpoints from outside it
(Keycloak, the gateway). The root is made by the CA on its first start and lives in the stack's volumes, so it is
copied out of the running cert-agent into .tls/ca.pem (gitignored) on every call: a stack recreated with a new CA
never leaves a stale root behind.

    python scripts/internal_ca.py               # prints the path of the root, for curl --cacert
    python scripts/internal_ca.py --curl-home   # prints a CURL_HOME whose .curlrc trusts it (any platform)
    from internal_ca import ssl_context         # an SSLContext that trusts only that root

Standard library only.
"""

from __future__ import annotations

import base64
import hashlib
import ssl
import subprocess
import sys
from functools import lru_cache
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parent.parent
ROOT_FILE = REPOSITORY / ".tls" / "ca.pem"


@lru_cache(maxsize=1)
def root_path() -> Path:
    result = subprocess.run(
        ["docker", "compose", "--project-directory", str(REPOSITORY), "exec", "-T", "cert-agent",
         "cat", "/home/step/ca.pem"],
        capture_output=True, check=False)
    pem = result.stdout.decode(errors="replace")
    if result.returncode != 0 or "BEGIN CERTIFICATE" not in pem:
        raise SystemExit("internal_ca: could not read the CA's root from cert-agent; is the stack up? "
                         + result.stderr.decode(errors="replace").strip())
    ROOT_FILE.parent.mkdir(exist_ok=True)
    ROOT_FILE.write_text(pem, encoding="ascii", newline="\n")
    return ROOT_FILE


@lru_cache(maxsize=1)
def ssl_context() -> ssl.SSLContext:
    """Verifies the server's chain to the internal root and its name, TLS 1.2 or newer."""
    context = ssl.create_default_context(cafile=str(root_path()))
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    return context


def _tlv(der: bytes, at: int) -> tuple[int, int, int]:
    """One DER element at `at`: its tag, where its content starts and where it ends."""
    tag, length, at = der[at], der[at + 1], at + 2
    if length & 0x80:
        size = length & 0x7F
        length, at = int.from_bytes(der[at:at + size], "big"), at + size
    return tag, at, at + length


def root_spki_sha256() -> str:
    """The base64 SHA-256 of the root's public key, for Chromium's --ignore-certificate-errors-spki-list: a chain that
    does not otherwise validate is accepted only if it contains this key, so a browser trusts this root and no other
    without an NSS database."""
    pem = root_path().read_text(encoding="ascii")
    der = base64.b64decode("".join(line for line in pem.splitlines() if not line.startswith("-----")))
    _, content, _ = _tlv(der, 0)                     # Certificate
    _, at, _ = _tlv(der, content)                    # tbsCertificate
    tag, _, end = _tlv(der, at)
    if tag == 0xA0:                                  # [0] version
        at = end
    for _ in range(5):                               # serial, signature, issuer, validity, subject
        _, _, at = _tlv(der, at)
    _, _, end = _tlv(der, at)                        # subjectPublicKeyInfo, whole
    return base64.b64encode(hashlib.sha256(der[at:end]).digest()).decode()


def curl_home() -> Path:
    """A directory whose .curlrc makes every curl verify against the root (export CURL_HOME=<it>). Windows' curl is
    built on Schannel: it ignores CURL_CA_BUNDLE, takes --cacert, and then asks for revocation data an internal CA
    without CRL or OCSP cannot give, so the file also says ssl-no-revoke, which other TLS backends ignore."""
    home = root_path().parent
    (home / ".curlrc").write_text(f'cacert = "{root_path().as_posix()}"\nssl-no-revoke\n', encoding="ascii",
                                  newline="\n")
    return home


if __name__ == "__main__":
    print(curl_home() if "--curl-home" in sys.argv[1:] else root_path())
    sys.exit(0)
