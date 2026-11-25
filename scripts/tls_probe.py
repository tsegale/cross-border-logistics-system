#!/usr/bin/env python3
"""Proves the transport rules of ADR 0040 against a running stack, from inside its network:

  every endpoint answers TLS 1.2 or newer with a certificate that verifies against the internal root, by its name;
  a plaintext request in the endpoint's own protocol is refused (HTTP, Postgres, Kafka, Redis, SMTP);
  the services' ports refuse a client without a certificate and one from another CA, even with an allowed name.

Run in a container on the compose network, with the certs volume's trust directory and a client certificate from a
CA the stack does not know (CI makes one with openssl):

  docker run --rm --network freightflow_default -v freightflow_certs:/certs:ro -v $PWD/scripts:/probe:ro \\
    -v /tmp/untrusted:/untrusted:ro python:3.15.2-alpine python -I /probe/tls_probe.py --json /probe-out.json

Exits 1 if any check fails. Standard library only.
"""

from __future__ import annotations

import argparse
import json
import socket
import ssl
import struct
import sys

ROOT = "/certs/trust/ca.pem"
TIMEOUT = 10

# host, port, protocol, whether it asks for a client certificate
ENDPOINTS = [
    ("api-gateway", 8080, "http", False),
    ("api-gateway", 8085, "http", False),
    ("banking-service", 8081, "http", True),
    ("logistics-service", 8082, "http", True),
    ("notification-service", 8083, "http", True),
    ("keycloak", 8443, "http", False),
    ("keycloak", 9000, "http", False),
    ("partner-simulator", 8090, "http", False),
    ("mailpit", 8025, "http", False),
    ("mailpit", 1025, "smtp", False),
    ("banking-db", 5432, "postgres", False),
    ("logistics-db", 5432, "postgres", False),
    ("notification-db", 5432, "postgres", False),
    ("keycloak-db", 5432, "postgres", False),
    ("kafka", 29092, "kafka", False),
    ("redis", 6379, "redis", False),
]
OPTIONAL = [("otel-collector", 4318, "http", False)]


def context(client: tuple[str, str] | None = None) -> ssl.SSLContext:
    tls = ssl.create_default_context(cafile=ROOT)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    if client:
        tls.load_cert_chain(*client)
    return tls


def connect(host: str, port: int, protocol: str) -> socket.socket:
    """A TCP connection, upgraded the way the protocol does it (Postgres's SSLRequest, SMTP's STARTTLS)."""
    raw = socket.create_connection((host, port), timeout=TIMEOUT)
    if protocol == "postgres":
        raw.sendall(struct.pack("!ii", 8, 80877103))
        if raw.recv(1) != b"S":
            raise RuntimeError("the server would not start TLS")
    elif protocol == "smtp":
        raw.recv(1024)
        raw.sendall(b"EHLO probe\r\n")
        raw.recv(4096)
        raw.sendall(b"STARTTLS\r\n")
        if not raw.recv(1024).startswith(b"220"):
            raise RuntimeError("the server would not start TLS")
    return raw


def tls_check(host: str, port: int, protocol: str, client: tuple[str, str] | None) -> str:
    with context(client).wrap_socket(connect(host, port, protocol), server_hostname=host) as tls:
        if protocol == "http":
            # The handshake can finish before a TLS 1.3 server has checked the client's certificate: a request
            # brings its verdict.
            tls.sendall(f"GET / HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n".encode())
            if not tls.recv(16).startswith(b"HTTP/"):
                raise RuntimeError("no HTTP answer over TLS")
        return f"{tls.version()} {tls.cipher()[0]}"


def plaintext_refused(host: str, port: int, protocol: str) -> str:
    """Speaks the protocol without TLS; refused means no answer that the protocol would act on."""
    with socket.create_connection((host, port), timeout=TIMEOUT) as raw:
        try:
            if protocol == "http":
                raw.sendall(f"GET / HTTP/1.1\r\nHost: {host}\r\n\r\n".encode())
                answer = raw.recv(512)
                # The server itself refuses, before any application code: Tomcat with 400 "This combination of host
                # and port requires TLS", Go's net/http with 400 "Client sent an HTTP request to an HTTPS server";
                # Netty, Vert.x and Python close the connection or send a TLS alert.
                if answer.startswith(b"HTTP/") and b" 400 " in answer[:16] and (b"TLS" in answer or b"HTTPS" in answer):
                    return "400, TLS required"
                if answer.startswith(b"HTTP/"):
                    return f"unexpected HTTP answer {answer[:16]!r}"
                return closed_or_alert(answer)
            if protocol == "postgres":
                user, database = b"probe", b"probe"
                body = struct.pack("!i", 196608) + b"user\0" + user + b"\0database\0" + database + b"\0\0"
                raw.sendall(struct.pack("!i", len(body) + 4) + body)
                answer = raw.recv(512)
                if answer[:1] == b"E" and b"28000" in answer:
                    return "refused by pg_hba (no encryption)"
                return f"unexpected {answer[:20]!r}"
            if protocol == "kafka":
                # ApiVersions v0, correlation id 1, client id "probe".
                request = struct.pack("!hhih", 18, 0, 1, 5) + b"probe"
                raw.sendall(struct.pack("!i", len(request)) + request)
                return closed_or_alert(raw.recv(64))
            if protocol == "redis":
                raw.sendall(b"PING\r\n")
                return closed_or_alert(raw.recv(64))
            if protocol == "smtp":
                raw.recv(1024)
                raw.sendall(b"EHLO probe\r\nMAIL FROM:<probe@freightflow.dev>\r\n")
                answer = b""
                while b"\r\n5" not in answer and not answer.startswith(b"5") and len(answer) < 4096:
                    chunk = raw.recv(1024)
                    if not chunk:
                        break
                    answer += chunk
                return "530 STARTTLS first" if b"530" in answer else f"unexpected {answer[-40:]!r}"
        except (ConnectionResetError, BrokenPipeError):
            return "reset"
        except socket.timeout:
            return "no answer"
    raise ValueError(protocol)


def closed_or_alert(answer: bytes) -> str:
    """Nothing, or a TLS alert record (content type 21): the server would not speak the protocol in plaintext."""
    if not answer:
        return "closed"
    if answer[:1] == b"\x15" and answer[1:2] == b"\x03":
        return "TLS alert"
    return f"unexpected {answer[:12]!r}"


def refused(host: str, port: int, protocol: str, client: tuple[str, str] | None) -> str:
    try:
        tls_check(host, port, protocol, client)
    except (ssl.SSLError, ConnectionResetError, BrokenPipeError, RuntimeError) as error:
        return type(error).__name__ + (f": {error.reason}" if isinstance(error, ssl.SSLError) else "")
    raise AssertionError("accepted")


def reachable(host: str, port: int) -> bool:
    try:
        socket.create_connection((host, port), timeout=3).close()
        return True
    except OSError:
        return False


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--untrusted-cert", default="/untrusted/cert.pem")
    parser.add_argument("--untrusted-key", default="/untrusted/key.pem")
    parser.add_argument("--json", help="write the results here")
    args = parser.parse_args()
    untrusted = (args.untrusted_cert, args.untrusted_key)

    rows, failed = [], 0
    endpoints = ENDPOINTS + [e for e in OPTIONAL if reachable(e[0], e[1])]
    for host, port, protocol, mutual in endpoints:
        checks = [("plaintext", lambda: plaintext_refused(host, port, protocol))]
        if mutual:
            # Verified with the CA's own client certificate is the CallerCertificateIntegrationTests' job: here, refusals.
            checks += [("no client certificate", lambda: refused(host, port, protocol, None)),
                       ("certificate from another CA", lambda: refused(host, port, protocol, untrusted))]
        else:
            checks.insert(0, ("verified TLS", lambda: tls_check(host, port, protocol, None)))
        for name, check in checks:
            try:
                outcome, ok = check(), True
                if name == "plaintext" and outcome.startswith("unexpected"):
                    ok = False
            except Exception as error:  # noqa: BLE001 any failure is a failed check, reported with its reason
                outcome, ok = f"{type(error).__name__}: {error}", False
            failed += not ok
            rows.append({"endpoint": f"{host}:{port}", "protocol": protocol, "check": name, "ok": ok,
                         "outcome": outcome})
            print(f"{'ok  ' if ok else 'FAIL'} {host}:{port:<6} {protocol:9} {name:28} {outcome}", flush=True)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as out:
            json.dump(rows, out, indent=2)
    print(f"{len(rows) - failed} of {len(rows)} checks passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
