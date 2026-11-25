#!/usr/bin/env python3
"""The internal CA's root on the host (ADR 0039), for the scripts that call the stack's TLS endpoints from outside it
(Keycloak, the gateway). The root is made by the CA on its first start and lives in the stack's volumes, so it is
copied out of the running cert-agent into .tls/ca.pem (gitignored) on every call: a stack recreated with a new CA
never leaves a stale root behind.

    python scripts/internal_ca.py          # prints the path of the root, for curl --cacert
    from internal_ca import ssl_context    # an SSLContext that trusts only that root

Standard library only.
"""

from __future__ import annotations

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


if __name__ == "__main__":
    print(root_path())
    sys.exit(0)
