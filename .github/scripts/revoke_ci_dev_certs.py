#!/usr/bin/env python3
"""Revoke the Apple Development certificates that earlier CI runs minted.

Every iOS release archives with automatic signing, the App Store Connect API key and
`-allowProvisioningUpdates`. A fresh runner has no development identity, so xcodebuild
mints a new development certificate through the API on every run and the private key
dies with the runner. Left alone they pile up until Apple's per-account cap refuses the
next one ("Choose a certificate to revoke") and the release fails.

This script lists the team's development certificates, logs them, and revokes only the
ones the API created. Certificates created through the App Store Connect API carry the
display name "Created via API" (the create request has no field to name them), e.g.
`name: "Apple Development: Created via API (TEAMID)"`. A certificate made in Xcode or the
portal is named after its person ("Apple Development: Simon Hull (…)") and is never
touched; neither is any distribution certificate.

Cleanup is best effort: any failure is a warning and the script exits 0. The archive may
still succeed under the cap, and a release must never fail on housekeeping alone.

Stdlib only (plus the `openssl` CLI), so it runs on a bare macOS runner. The token and
key are never printed.

Environment: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH (path to the .p8 private key),
optional GITHUB_STEP_SUMMARY.
"""

from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

API_ROOT = "https://api.appstoreconnect.apple.com"
DEVELOPMENT_TYPES = ("DEVELOPMENT", "IOS_DEVELOPMENT")
CI_MINTED_MARKER = "Created via API"
TOKEN_LIFETIME_SECONDS = 15 * 60  # Apple rejects tokens that live longer than 20 minutes.


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _read_der_length(der: bytes, offset: int) -> tuple[int, int]:
    first = der[offset]
    if first < 0x80:
        return first, offset + 1
    count = first & 0x7F
    return int.from_bytes(der[offset + 1 : offset + 1 + count], "big"), offset + 1 + count


def der_signature_to_raw(der: bytes, size: int = 32) -> bytes:
    """ECDSA-Sig-Value ::= SEQUENCE { r INTEGER, s INTEGER } → r||s, each `size` bytes (JWS ES256)."""
    if der[0] != 0x30:
        raise ValueError("signature is not a DER SEQUENCE")
    _, offset = _read_der_length(der, 1)
    parts = []
    for _ in range(2):
        if der[offset] != 0x02:
            raise ValueError("signature component is not a DER INTEGER")
        length, offset = _read_der_length(der, offset + 1)
        value = int.from_bytes(der[offset : offset + length], "big")
        parts.append(value.to_bytes(size, "big"))
        offset += length
    return parts[0] + parts[1]


def build_token(key_path: str, key_id: str, issuer_id: str, now: int | None = None) -> str:
    """An ES256 App Store Connect API token, signed by the `openssl` CLI."""
    issued_at = int(time.time()) if now is None else now
    header = {"alg": "ES256", "kid": key_id, "typ": "JWT"}
    payload = {
        "iss": issuer_id,
        "iat": issued_at,
        "exp": issued_at + TOKEN_LIFETIME_SECONDS,
        "aud": "appstoreconnect-v1",
    }
    signing_input = ".".join(
        b64url(json.dumps(part, separators=(",", ":")).encode()) for part in (header, payload)
    )
    der = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", key_path],
        input=signing_input.encode(),
        capture_output=True,
        check=True,
    ).stdout
    return f"{signing_input}.{b64url(der_signature_to_raw(der))}"


def is_ci_minted(cert: dict) -> bool:
    attributes = cert.get("attributes") or {}
    if attributes.get("certificateType") not in DEVELOPMENT_TYPES:
        return False
    names = (attributes.get("name") or "", attributes.get("displayName") or "")
    return any(CI_MINTED_MARKER in name for name in names)


def select_ci_minted(certs: list[dict]) -> list[dict]:
    return [cert for cert in certs if is_ci_minted(cert)]


def describe(cert: dict) -> str:
    a = cert.get("attributes") or {}
    return (
        f"{cert.get('id')} | {a.get('name')} | displayName={a.get('displayName')} | "
        f"{a.get('certificateType')} | {a.get('platform')} | expires {a.get('expirationDate')}"
    )


def _request(method: str, url: str, token: str) -> bytes:
    request = urllib.request.Request(url, method=method, headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def list_development_certificates(token: str) -> list[dict]:
    url = (
        f"{API_ROOT}/v1/certificates?filter[certificateType]={','.join(DEVELOPMENT_TYPES)}&limit=200"
    )
    certs: list[dict] = []
    while url:
        page = json.loads(_request("GET", url, token))
        certs.extend(page.get("data", []))
        url = (page.get("links") or {}).get("next")
    return certs


def revoke(token: str, cert_id: str) -> None:
    _request("DELETE", f"{API_ROOT}/v1/certificates/{cert_id}", token)


def _http_error_detail(error: Exception) -> str:
    if isinstance(error, urllib.error.HTTPError):
        try:
            body = json.loads(error.read())
            details = "; ".join(e.get("detail") or e.get("title", "") for e in body.get("errors", []))
        except Exception:  # noqa: BLE001 — an unreadable error body still gets reported
            details = ""
        return f"HTTP {error.code} {details}".strip()
    return f"{type(error).__name__}: {error}"


def run(token_factory=build_token, lister=list_development_certificates, revoker=revoke) -> list[str]:
    """Returns the lines for the step summary. Never raises."""
    summary = ["### Development certificates (CI cleanup)"]

    def warn(message: str) -> None:
        print(f"::warning::{message}")
        summary.append(f"> ⚠️ {message}")

    try:
        token = token_factory(os.environ["ASC_KEY_PATH"], os.environ["ASC_KEY_ID"], os.environ["ASC_ISSUER_ID"])
        certs = lister(token)
    except Exception as error:  # noqa: BLE001 — cleanup is best effort
        warn(f"Could not list development certificates, skipping cleanup ({_http_error_detail(error)}).")
        return summary

    print(f"Found {len(certs)} development certificate(s):")
    summary.append("")
    summary.append("| id | name | type | expires | action |")
    summary.append("|---|---|---|---|---|")
    to_revoke = {cert["id"] for cert in select_ci_minted(certs)}
    for cert in certs:
        a = cert.get("attributes") or {}
        action = "revoke" if cert["id"] in to_revoke else "keep"
        print(f"  [{action}] {describe(cert)}")
        if action == "revoke":
            try:
                revoker(token, cert["id"])
                action = "revoked"
                print(f"  revoked {cert['id']}")
            except Exception as error:  # noqa: BLE001
                action = "revoke failed"
                warn(f"Could not revoke {cert['id']} ({_http_error_detail(error)}).")
        summary.append(
            f"| {cert['id']} | {a.get('name')} | {a.get('certificateType')} | {a.get('expirationDate')} | {action} |"
        )
    if not to_revoke:
        print(f"No development certificate is marked '{CI_MINTED_MARKER}'; nothing to revoke.")
    return summary


def main() -> int:
    summary = run()
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as handle:
            handle.write("\n".join(summary) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
