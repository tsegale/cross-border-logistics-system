#!/usr/bin/env python3
"""Every running container of the stack is hardened as ADR 0041 says, checked on the containers themselves (docker
inspect), not on the compose file: its root filesystem read-only, every Linux capability dropped and none added back,
no-new-privileges, a user other than root, and no Docker socket. docker-api, the read-only Docker API proxy, is the one
container that runs as root, for the host's socket, and holds no capability either.

    python scripts/check_hardening.py      # exits 1 if any container breaks a rule

Standard library only.
"""

from __future__ import annotations

import json
import subprocess
import sys

ROOT_ALLOWED = {"docker-api": "the host's Docker socket belongs to its root; no capability"}
SOCKET_ALLOWED = {"docker-api"}


def containers() -> list[dict]:
    # Every running container of the project, whichever profiles started it.
    ids = subprocess.run(["docker", "ps", "-q", "--filter", "label=com.docker.compose.project=freightflow"],
                         capture_output=True, text=True, check=True).stdout.split()
    if not ids:
        raise SystemExit("check_hardening: no running container; is the stack up?")
    return json.loads(subprocess.run(["docker", "inspect", *ids], capture_output=True, text=True, check=True).stdout)


def problems(container: dict) -> list[str]:
    service = container["Config"]["Labels"].get("com.docker.compose.service", container["Name"])
    host = container["HostConfig"]
    found = []
    if not host.get("ReadonlyRootfs"):
        found.append("root filesystem writable")
    if "ALL" not in [c.upper() for c in host.get("CapDrop") or []]:
        found.append(f"capabilities not all dropped ({host.get('CapDrop')})")
    if host.get("CapAdd"):
        found.append(f"capabilities added back ({host.get('CapAdd')})")
    if not any(o.replace("=", ":") == "no-new-privileges:true" or o == "no-new-privileges"
               for o in host.get("SecurityOpt") or []):
        found.append("no-new-privileges not set")
    if host.get("Privileged"):
        found.append("privileged")
    user = (container["Config"].get("User") or "").split(":")[0]
    if user in ("", "0", "root") and service not in ROOT_ALLOWED:
        found.append(f"runs as root (user {user or 'unset, the image default'})")
    sockets = [m["Source"] for m in container.get("Mounts", []) if m.get("Source", "").endswith("docker.sock")]
    if sockets and service not in SOCKET_ALLOWED:
        found.append("mounts the Docker socket")
    return found


def main() -> None:
    failed = 0
    for container in sorted(containers(), key=lambda c: c["Name"]):
        service = container["Config"]["Labels"].get("com.docker.compose.service", container["Name"])
        found = problems(container)
        failed += bool(found)
        user = container["Config"].get("User") or "(image default)"
        note = f"  root: {ROOT_ALLOWED[service]}" if service in ROOT_ALLOWED else ""
        print(f"{'FAIL' if found else 'ok  '} {service:22} user {user:12} {'; '.join(found)}{note}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
