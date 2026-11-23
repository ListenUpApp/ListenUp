#!/usr/bin/env python3
"""Send a freshly uploaded build to external TestFlight testers.

The Release workflow uploads each iOS build to App Store Connect and stops there. External
testers only get a build once it is added to their group and has passed Beta App Review, and
each release is a new version, which Apple always reviews in full. Any time between the upload
and someone submitting it by hand is added to that wait.

This script closes that gap:

1. waits for the build (MARKETING_VERSION, build BUILD_NUMBER) to finish processing;
2. adds it to the app's external beta groups (all of them, or the ones TESTFLIGHT_GROUPS names);
3. submits it for Beta App Review, unless it is already waiting, in review or approved.

A failure exits non-zero, so the release says so and the build can still be sent by hand. The
upload itself is never at risk: it finished before this runs.

Stdlib only (plus the `openssl` CLI); the token and key are never printed.

Environment: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH (path to the .p8 private key), BUNDLE_ID,
MARKETING_VERSION, BUILD_NUMBER, optional TESTFLIGHT_GROUPS (comma-separated group names),
optional GITHUB_STEP_SUMMARY.
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.parse
import urllib.request
from typing import Callable

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from revoke_ci_dev_certs import API_ROOT, _http_error_detail, build_token  # noqa: E402

PROCESSING_TIMEOUT_SECONDS = 90 * 60
POLL_INTERVAL_SECONDS = 60

# externalBuildState values (ExternalBetaState) for which submitting again would be refused or pointless.
ALREADY_SUBMITTED = {
    "WAITING_FOR_BETA_REVIEW",
    "IN_BETA_REVIEW",
    "BETA_APPROVED",
    "READY_FOR_BETA_TESTING",
    "IN_BETA_TESTING",
}
SUBMITTABLE = "READY_FOR_BETA_SUBMISSION"


class ReleaseError(Exception):
    """A step that needs a person: the message says what went wrong."""


Api = Callable[[str, str, dict | None], dict]


def make_api(key_path: str, key_id: str, issuer_id: str) -> Api:
    """A JSON:API caller. A fresh token per call, since a processing wait outlives one token."""

    def call(method: str, path: str, body: dict | None = None) -> dict:
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(
            path if path.startswith("http") else f"{API_ROOT}{path}",
            data=data,
            method=method,
            headers={
                "Authorization": f"Bearer {build_token(key_path, key_id, issuer_id)}",
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
        return json.loads(raw) if raw else {}

    return call


def find_app_id(api: Api, bundle_id: str) -> str:
    query = urllib.parse.urlencode({"filter[bundleId]": bundle_id, "fields[apps]": "bundleId"})
    apps = [app for app in api("GET", f"/v1/apps?{query}", None).get("data", [])
            if (app.get("attributes") or {}).get("bundleId") == bundle_id]
    if not apps:
        raise ReleaseError(f"No App Store Connect app has bundle ID {bundle_id}.")
    return apps[0]["id"]


def find_build(api: Api, app_id: str, marketing_version: str, build_number: str) -> dict | None:
    query = urllib.parse.urlencode(
        {
            "filter[app]": app_id,
            "filter[version]": build_number,
            "filter[preReleaseVersion.version]": marketing_version,
            "include": "buildBetaDetail",
            "limit": 1,
        }
    )
    page = api("GET", f"/v1/builds?{query}", None)
    builds = page.get("data", [])
    if not builds:
        return None
    build = builds[0]
    details = [i for i in page.get("included", []) if i.get("type") == "buildBetaDetails"]
    build["_externalBuildState"] = (details[0].get("attributes") or {}).get("externalBuildState") if details else None
    return build


def wait_until_processed(
    api: Api,
    app_id: str,
    marketing_version: str,
    build_number: str,
    *,
    timeout: float = PROCESSING_TIMEOUT_SECONDS,
    interval: float = POLL_INTERVAL_SECONDS,
    clock: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
) -> dict:
    """The build once Apple has processed it. It may not be listed at all for the first minutes."""
    deadline = clock() + timeout
    while True:
        build = find_build(api, app_id, marketing_version, build_number)
        state = (build or {}).get("attributes", {}).get("processingState")
        # The beta state can trail processing by a moment; until it settles it reads PROCESSING too.
        if state == "VALID" and build.get("_externalBuildState") != "PROCESSING":
            return build
        if state in ("FAILED", "INVALID"):
            raise ReleaseError(f"Build {marketing_version} ({build_number}) failed processing: {state}.")
        if clock() >= deadline:
            seen = state or "not listed yet"
            raise ReleaseError(
                f"Build {marketing_version} ({build_number}) was still {seen} after {int(timeout // 60)} minutes."
            )
        print(f"Build {marketing_version} ({build_number}): {state or 'not listed yet'}; checking again shortly.")
        sleep(interval)


def external_groups(api: Api, app_id: str, wanted_names: list[str]) -> list[dict]:
    groups = [
        group
        for group in api("GET", f"/v1/apps/{app_id}/betaGroups?limit=200", None).get("data", [])
        if not (group.get("attributes") or {}).get("isInternalGroup")
    ]
    if not wanted_names:
        if not groups:
            raise ReleaseError("The app has no external TestFlight group to send the build to.")
        return groups
    by_name = {(group.get("attributes") or {}).get("name"): group for group in groups}
    missing = [name for name in wanted_names if name not in by_name]
    if missing:
        raise ReleaseError(f"No external TestFlight group is named {', '.join(missing)}.")
    return [by_name[name] for name in wanted_names]


def add_to_groups(api: Api, build_id: str, groups: list[dict]) -> None:
    api(
        "POST",
        f"/v1/builds/{build_id}/relationships/betaGroups",
        {"data": [{"type": "betaGroups", "id": group["id"]} for group in groups]},
    )


def submit_for_review(api: Api, build_id: str) -> None:
    api(
        "POST",
        "/v1/betaAppReviewSubmissions",
        {
            "data": {
                "type": "betaAppReviewSubmissions",
                "relationships": {"build": {"data": {"type": "builds", "id": build_id}}},
            }
        },
    )


def run(api: Api, bundle_id: str, marketing_version: str, build_number: str, wanted_groups: list[str], **wait) -> list[str]:
    """Returns the step-summary lines. Raises ReleaseError (or an HTTP error) when a person is needed."""
    label = f"{marketing_version} ({build_number})"
    app_id = find_app_id(api, bundle_id)
    build = wait_until_processed(api, app_id, marketing_version, build_number, **wait)
    groups = external_groups(api, app_id, wanted_groups)
    add_to_groups(api, build["id"], groups)
    names = ", ".join((group.get("attributes") or {}).get("name", group["id"]) for group in groups)
    summary = [f"### TestFlight: build {label}", "", f"Added to external groups: {names}."]

    state = build.get("_externalBuildState")
    if state in ALREADY_SUBMITTED:
        summary.append(f"Beta App Review: already {state}, nothing to submit.")
    elif state == SUBMITTABLE:
        submit_for_review(api, build["id"])
        summary.append("Beta App Review: submitted.")
    else:
        raise ReleaseError(f"Build {label} is {state or 'in an unknown state'}, which cannot be submitted for review.")
    return summary


def main() -> int:
    env = os.environ
    api = make_api(env["ASC_KEY_PATH"], env["ASC_KEY_ID"], env["ASC_ISSUER_ID"])
    wanted = [name.strip() for name in env.get("TESTFLIGHT_GROUPS", "").split(",") if name.strip()]
    try:
        summary = run(api, env["BUNDLE_ID"], env["MARKETING_VERSION"], env["BUILD_NUMBER"], wanted)
        status = 0
    except Exception as error:  # noqa: BLE001 — every failure is reported the same way
        detail = str(error) if isinstance(error, ReleaseError) else _http_error_detail(error)
        print(f"::error::Could not send the build to external testers: {detail}")
        summary = [
            "### TestFlight",
            "",
            f"> ⚠️ Could not send the build to external testers: {detail}",
            "> The upload succeeded; add the build to the external group in App Store Connect by hand.",
        ]
        status = 1
    print("\n".join(summary))
    summary_path = env.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as handle:
            handle.write("\n".join(summary) + "\n")
    return status


if __name__ == "__main__":
    sys.exit(main())
