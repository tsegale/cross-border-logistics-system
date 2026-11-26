# 43. Signed images and a pinned supply chain

Date: 2026-11-26 · Status: Accepted · Builds on ADR 0016 (CI), ADR 0041 (hardened containers)

## Context
CI built the four service images, scanned them with Trivy and kept an SBOM as an artifact. Nothing tied a running
image to that build:
- **Nothing was published or signed.** The stack test built its own images again from source.
- **Actions were taken by major tag** (`actions/checkout@v7`), which whoever controls an action's repository can
  move.
- **Container images were taken by tag** in compose and in the Dockerfiles: a pushed tag changes what runs.

The Phase 7 brief asks for:
- Actions pinned to SHAs;
- keyless cosign signatures, with SLSA provenance and SBOM attestations, verified before compose runs the images;
- an OpenSSF Scorecard.

## Sources, checked 2026-11-26
- Sigstore cosign v3.1.6 (github.com/sigstore/cosign/releases, 2026-11-25), installed by `sigstore/cosign-installer`
  v4.1.4: `cosign sign`, `cosign attest --type spdxjson`, and `verify` and `verify-attestation` with
  `--certificate-identity` and `--certificate-oidc-issuer`.
- SLSA v1.0 provenance (slsa.dev/spec/v1.0/provenance), produced by `actions/attest-build-provenance` v4.2.2, which
  signs it through Sigstore with the workflow's OIDC identity and can push it to the registry. It is verified with
  `gh attestation verify --signer-workflow`.
- GitHub Actions security hardening guide: pin third-party actions to a full-length commit SHA.
- OpenSSF Scorecard action v2.6.0 (2026-11-19), its documented workflow (top-level `read-all`, SARIF upload).

## Decision
- **Images are published, signed and attested on main only.** The docker job pushes each image to
  `ghcr.io/<owner>/freightflow-<service>:<commit>`, then works by its digest:
  - `cosign sign` signs it keyless: Fulcio's certificate names this repository's `ci.yml` on `refs/heads/main`, and
    the signature is recorded in Rekor;
  - `cosign attest` attaches the Syft SPDX SBOM as a signed attestation;
  - `attest-build-provenance` adds SLSA v1 provenance.

  BuildKit's own unsigned provenance and SBOM are switched off. Pull requests build and scan the images, and push
  nothing.
- **The stack runs only verified images.** On main, `default-stack` waits for the docker job. Before compose runs
  anything, `scripts/verify_images.py` checks each digest's signature, SBOM attestation and provenance against the
  workflow's identity. It writes `compose.images.yml`, which replaces each service's build with the verified digest.
  A failed check stops the job. As its own proof, the script verifies the first image against another workflow's
  identity, and that check must be refused.
- **Every action is pinned to a commit SHA**, with its version beside it for Dependabot. The line-endings job fails on
  any `uses:` that is not.
- **Every container image is pinned by its index digest** beside its tag: compose, the Dockerfiles, CI's `docker run`
  steps and the scripts. Index digests keep both platforms. Dependabot moves tag and digest together.
- **OpenSSF Scorecard** runs weekly and on main. Its SARIF goes to code scanning, and its results go to the public
  Scorecard API.

## Consequences
- **A running image can be traced to a commit and a workflow run**, and an image changed after the build fails
  verification.
- **`default-stack` now waits for the Java tests and the image builds on every run**, because a job cannot depend on
  another for main only. Pull requests pay that wait too.
- **A developer's `docker compose up` still builds from source:** verification is CI's, for now. Deploying from the
  registry and enforcing signatures at admission belong to Phase 8 (a Kyverno or Sigstore policy controller rule
  with the same identity).
- **Signing depends on public Sigstore** (Fulcio, Rekor) and GHCR being up. The Rekor entries are public and
  permanent; they name the repository, the workflow and the image digests, which are public already.
- **The infrastructure images built in compose (`ca`, `keycloak`) are not signed.** They are thin layers over
  digest-pinned upstream images, built by compose. Signing them is the same job matrix, once they are deployed from
  a registry.
