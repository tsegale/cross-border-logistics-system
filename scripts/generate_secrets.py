#!/usr/bin/env python3
"""Generate every secret the stack needs into secrets/ (git-ignored). Prints no values.

    python scripts/generate_secrets.py

Compose mounts the single-value files as secrets (/run/secrets/<name>): the databases read POSTGRES_PASSWORD_FILE and
the Spring services read them through a config tree, so no password is in compose, application.yml or an image.
Keycloak cannot read a secret from a file, so its values go in secrets/keycloak.env, which only the keycloak
container loads; the realm file refers to them by name (infra/keycloak/realm/freightflow-realm.json, ADR 0016).

Running it again keeps existing values and only adds missing ones: a database and a realm are initialised once, so
changing a value here later would not change it where it is already in use. Standard library only.
"""

from __future__ import annotations

import os
import secrets
from pathlib import Path

SECRETS_DIR = Path(__file__).resolve().parent.parent / "secrets"
KEYCLOAK_ENV = SECRETS_DIR / "keycloak.env"

# One file per value, mounted as a compose secret of the same name.
FILES = (
    "banking_db_password",
    "logistics_db_password",
    "keycloak_db_password",
    "logistics_client_secret",
)
# Keycloak's environment: the realm's ${PLACEHOLDERS} and its own admin and database credentials.
KEYCLOAK_GENERATED = (
    "KC_BOOTSTRAP_ADMIN_PASSWORD",
    "KEYCLOAK_CARRIER_WALVIS_HAULAGE_SECRET",
    "KEYCLOAK_PASSWORD_CUSTOMER_NAMIB_EXPORTS",
    "KEYCLOAK_PASSWORD_CUSTOMER_HANSEATIC_IMPORTS",
    "KEYCLOAK_PASSWORD_OPERATOR",
    "KEYCLOAK_PASSWORD_AUDITOR",
)


def read_env_file(path: Path = KEYCLOAK_ENV) -> dict[str, str]:
    if not path.exists():
        return {}
    pairs = (line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines() if "=" in line)
    return {key.strip(): value.strip() for key, value in pairs}


def read_secret(name: str) -> str:
    return (SECRETS_DIR / name).read_text(encoding="utf-8").strip()


def _private(path: Path, text: str) -> None:
    """Only read by compose on the host (env_file), so readable by this user only."""
    path.write_text(text, encoding="utf-8", newline="\n")
    os.chmod(path, 0o600)


def _mounted(path: Path, text: str) -> None:
    """A compose secret: bind-mounted with its host mode and read by the container's own non-root user, whose uid
    is not this user's. 0600 makes it unreadable there (Docker Desktop on Windows hides this). The directory is 0700,
    so on the host only this user can reach the file anyway."""
    path.write_text(text, encoding="utf-8", newline="\n")
    os.chmod(path, 0o644)


def _value() -> str:
    return secrets.token_hex(24)  # hex: nothing compose, a shell or a JDBC URL would interpret


def main() -> None:
    SECRETS_DIR.mkdir(parents=True, exist_ok=True)
    os.chmod(SECRETS_DIR, 0o700)
    added = 0
    for name in FILES:
        path = SECRETS_DIR / name
        if not path.exists():
            _mounted(path, _value() + "\n")
            added += 1
        else:
            os.chmod(path, 0o644)  # files made by an earlier version were 0600
    env = read_env_file()
    for key in KEYCLOAK_GENERATED:
        if key not in env:
            env[key] = _value()
            added += 1
    # Shared with the file-based secrets, so the database and logistics see the same values as Keycloak.
    env["KC_DB_PASSWORD"] = read_secret("keycloak_db_password")
    env["KEYCLOAK_LOGISTICS_SERVICE_SECRET"] = read_secret("logistics_client_secret")
    _private(KEYCLOAK_ENV, "".join(f"{key}={value}\n" for key, value in sorted(env.items())))
    print(f"secrets/: {added} generated, the rest kept")


if __name__ == "__main__":
    main()
