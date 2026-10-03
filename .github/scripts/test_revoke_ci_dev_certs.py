#!/usr/bin/env python3
"""Offline tests for revoke_ci_dev_certs.py. Run: python3 .github/scripts/test_revoke_ci_dev_certs.py"""

from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import revoke_ci_dev_certs as subject  # noqa: E402


def cert(cert_id: str, name: str, display_name: str, cert_type: str) -> dict:
    return {
        "type": "certificates",
        "id": cert_id,
        "attributes": {
            "name": name,
            "displayName": display_name,
            "certificateType": cert_type,
            "platform": "IOS",
            "expirationDate": "2027-10-01T00:00:00.000+00:00",
        },
    }


CANNED = [
    cert("CI1", "Apple Development: Created via API (GSTW88284W)", "Created via API", "DEVELOPMENT"),
    cert("CI2", "iOS Development: Created via API", "Created via API", "IOS_DEVELOPMENT"),
    cert("OWNER", "Apple Development: Simon Hull (ABCDE12345)", "Simon Hull", "DEVELOPMENT"),
    cert("DIST", "Apple Distribution: Created via API (GSTW88284W)", "Created via API", "DISTRIBUTION"),
]


def b64url_decode(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def raw_to_der(raw: bytes) -> bytes:
    def integer(value: bytes) -> bytes:
        value = value.lstrip(b"\x00") or b"\x00"
        if value[0] & 0x80:
            value = b"\x00" + value
        return b"\x02" + bytes([len(value)]) + value

    body = integer(raw[:32]) + integer(raw[32:])
    return b"\x30" + bytes([len(body)]) + body


class SelectionTest(unittest.TestCase):
    def test_only_api_minted_development_certificates_are_selected(self):
        self.assertEqual([c["id"] for c in subject.select_ci_minted(CANNED)], ["CI1", "CI2"])

    def test_run_revokes_exactly_the_selected_and_keeps_going_on_failure(self):
        revoked = []

        def revoker(_token, cert_id):
            revoked.append(cert_id)
            if cert_id == "CI1":
                raise RuntimeError("boom")

        env = {"ASC_KEY_PATH": "k", "ASC_KEY_ID": "i", "ASC_ISSUER_ID": "s"}
        with mock.patch.dict(os.environ, env), mock.patch("builtins.print"):
            summary = subject.run(lambda *_: "token", lambda _t: CANNED, revoker)
        self.assertEqual(revoked, ["CI1", "CI2"])
        text = "\n".join(summary)
        self.assertIn("| CI1 |", text)
        self.assertIn("revoke failed", text)
        self.assertIn("| OWNER | Apple Development: Simon Hull (ABCDE12345) | DEVELOPMENT | 2027-10-01T00:00:00.000+00:00 | keep |", text)
        self.assertIn("| DIST |", text)

    def test_run_never_raises_when_listing_fails(self):
        def lister(_t):
            raise OSError("network down")

        env = {"ASC_KEY_PATH": "k", "ASC_KEY_ID": "i", "ASC_ISSUER_ID": "s"}
        with mock.patch.dict(os.environ, env), mock.patch("builtins.print"):
            summary = subject.run(lambda *_: "token", lister, lambda *_: self.fail("revoked"))
        self.assertIn("skipping cleanup", "\n".join(summary))

    def test_run_never_raises_without_credentials(self):
        with mock.patch.dict(os.environ, {}, clear=True), mock.patch("builtins.print"):
            summary = subject.run()
        self.assertIn("skipping cleanup", "\n".join(summary))


class TokenTest(unittest.TestCase):
    def test_token_is_a_valid_es256_jwt(self):
        with tempfile.TemporaryDirectory() as tmp:
            key = os.path.join(tmp, "AuthKey.p8")
            pub = os.path.join(tmp, "pub.pem")
            sig = os.path.join(tmp, "sig.der")
            subprocess.run(
                ["openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", key],
                check=True, capture_output=True,
            )
            subprocess.run(["openssl", "pkey", "-in", key, "-pubout", "-out", pub], check=True, capture_output=True)

            # Many tokens, so short r/s values (leading zero bytes) get exercised too.
            for _ in range(40):
                token = subject.build_token(key, "KEY123", "issuer-uuid", now=1_700_000_000)
                header_b64, payload_b64, signature_b64 = token.split(".")
                self.assertEqual(
                    json.loads(b64url_decode(header_b64)), {"alg": "ES256", "kid": "KEY123", "typ": "JWT"}
                )
                self.assertEqual(
                    json.loads(b64url_decode(payload_b64)),
                    {"iss": "issuer-uuid", "iat": 1_700_000_000, "exp": 1_700_000_900, "aud": "appstoreconnect-v1"},
                )
                raw = b64url_decode(signature_b64)
                self.assertEqual(len(raw), 64)
                with open(sig, "wb") as handle:
                    handle.write(raw_to_der(raw))
                verified = subprocess.run(
                    ["openssl", "dgst", "-sha256", "-verify", pub, "-signature", sig],
                    input=f"{header_b64}.{payload_b64}".encode(), capture_output=True,
                )
                self.assertEqual(verified.returncode, 0, verified.stdout + verified.stderr)

    def test_der_conversion_pads_short_components(self):
        der = bytes.fromhex("3006020101020200ff")
        self.assertEqual(subject.der_signature_to_raw(der), (1).to_bytes(32, "big") + (255).to_bytes(32, "big"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
