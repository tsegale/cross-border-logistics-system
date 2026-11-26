"""Verify the service images CI built before compose runs them (ADR 0043).

For each image the docker job pushed (a file per service under --digests, holding `<repository>@sha256:<digest>`):
- its cosign signature, keyless: the certificate must name this repository's CI workflow, on this ref, and GitHub's
  OIDC issuer;
- its SBOM, an SPDX attestation signed by the same identity;
- its SLSA provenance (v1), attested by actions/attest-build-provenance for the same workflow.

Then it writes a compose override that runs exactly those digests instead of building, and proves the check can fail:
the first image is verified once more against another workflow's identity, which must be refused.

    python scripts/verify_images.py --digests images --override compose.images.yml

Needs cosign and gh on the PATH, GITHUB_REPOSITORY, GITHUB_REF and GH_TOKEN, as in GitHub Actions.
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

ISSUER = "https://token.actions.githubusercontent.com"
WORKFLOW = ".github/workflows/ci.yml"


def run(command: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(command, capture_output=True, text=True, timeout=300)


def identity(repository: str, ref: str, workflow: str = WORKFLOW) -> list[str]:
    return ["--certificate-identity", f"https://github.com/{repository}/{workflow}@{ref}",
            "--certificate-oidc-issuer", ISSUER]


def verify(reference: str, repository: str, ref: str) -> list[str]:
    """The checks that failed for one image, empty when all passed."""
    failed = []
    checks = {
        "signature": ["cosign", "verify", *identity(repository, ref), reference],
        "sbom": ["cosign", "verify-attestation", "--type", "spdxjson", *identity(repository, ref), reference],
        "provenance": ["gh", "attestation", "verify", f"oci://{reference}", "--repo", repository,
                       "--signer-workflow", f"{repository}/{WORKFLOW}",
                       "--predicate-type", "https://slsa.dev/provenance/v1"],
    }
    for name, command in checks.items():
        result = run(command)
        if result.returncode != 0:
            failed.append(f"{name}: {(result.stderr or result.stdout).strip().splitlines()[-1:]}")
    return failed


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--digests", required=True, help="one file per service, named after it")
    parser.add_argument("--override", required=True, help="the compose override to write")
    args = parser.parse_args()
    repository, ref = os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_REF"]

    images = {path.stem: path.read_text().strip() for path in sorted(Path(args.digests).glob("*.digest"))}
    if not images:
        sys.exit("no image digests to verify")
    problems = {}
    for service, reference in images.items():
        failed = verify(reference, repository, ref)
        print(f"{service:22} {reference.split('@')[1][:19]}  {'verified' if not failed else 'REFUSED'}", flush=True)
        if failed:
            problems[service] = failed
    for service, failed in problems.items():
        for line in failed:
            print(f"  {service}: {line}")

    # The check refuses what it should: the same signature, expected from another workflow, does not verify.
    first = next(iter(images.values()))
    foreign = run(["cosign", "verify", *identity(repository, ref, ".github/workflows/live-apis.yml"), first])
    if foreign.returncode == 0:
        problems["self-test"] = ["a signature verified against another workflow's identity"]
    print(f"{'self-test':22} another workflow's identity {'refused' if foreign.returncode else 'ACCEPTED'}")
    if problems:
        sys.exit(1)

    # Compose runs the verified digests, never a build: !reset drops each service's build section.
    lines = ["# Written by scripts/verify_images.py: the images CI verified, by digest (ADR 0043).", "services:"]
    for service, reference in images.items():
        lines += [f"  {service}:", f"    image: {reference}", "    build: !reset null", "    pull_policy: always"]
    Path(args.override).write_text("\n".join(lines) + "\n")
    print(f"{args.override}: {len(images)} images by digest")


if __name__ == "__main__":
    main()
