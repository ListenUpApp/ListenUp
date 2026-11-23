#!/usr/bin/env python3
"""Offline tests for submit_testflight_beta.py. Run: python3 .github/scripts/test_submit_testflight_beta.py"""

from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import submit_testflight_beta as subject  # noqa: E402

BUNDLE = "com.calypsan.listenup.client.ios"


class FakeAppStoreConnect:
    """In-memory App Store Connect: answers the four GETs and records the two POSTs."""

    def __init__(self, build_states, external_state="READY_FOR_BETA_SUBMISSION", groups=None):
        # Each poll pops the next processing state; None means the build is not listed yet.
        self.build_states = list(build_states)
        self.external_state = external_state
        self.groups = groups if groups is not None else [
            ("g-int", "App Store Connect Users", True),
            ("g-ext", "Public", False),
        ]
        self.posts: list[tuple[str, dict]] = []

    def __call__(self, method, path, body):
        if method == "POST":
            self.posts.append((path, body))
            return {}
        if path.startswith("/v1/apps?"):
            return {"data": [{"id": "app1", "attributes": {"bundleId": BUNDLE}}]}
        if path.startswith("/v1/builds?"):
            state = self.build_states.pop(0) if len(self.build_states) > 1 else self.build_states[0]
            if state is None:
                return {"data": []}
            external = self.external_state
            if isinstance(state, tuple):
                state, external = state
            return {
                "data": [{"id": "build1", "attributes": {"processingState": state}}],
                "included": [{"type": "buildBetaDetails", "attributes": {"externalBuildState": external}}],
            }
        if path.startswith("/v1/apps/app1/betaGroups"):
            return {
                "data": [
                    {"id": gid, "attributes": {"name": name, "isInternalGroup": internal}}
                    for gid, name, internal in self.groups
                ]
            }
        raise AssertionError(f"unexpected {method} {path}")

    def posted(self, suffix):
        return [body for path, body in self.posts if path.endswith(suffix)]


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


def run(api, groups=(), clock=None):
    clock = clock or FakeClock()
    return subject.run(api, BUNDLE, "0.9.16", "412", list(groups), clock=clock, sleep=clock.sleep)


class SubmitTestFlightBetaTest(unittest.TestCase):
    def test_a_processed_build_goes_to_every_external_group_and_into_review(self):
        api = FakeAppStoreConnect(["VALID"])
        summary = run(api)
        self.assertEqual(api.posted("/relationships/betaGroups"), [{"data": [{"type": "betaGroups", "id": "g-ext"}]}])
        [submission] = api.posted("/v1/betaAppReviewSubmissions")
        self.assertEqual(submission["data"]["relationships"]["build"]["data"], {"type": "builds", "id": "build1"})
        self.assertIn("Beta App Review: submitted.", summary)

    def test_it_waits_while_the_build_is_unlisted_or_processing(self):
        clock = FakeClock()
        api = FakeAppStoreConnect([None, "PROCESSING", ("VALID", "PROCESSING"), "VALID"])
        run(api, clock=clock)
        self.assertEqual(clock.now, 3 * subject.POLL_INTERVAL_SECONDS)
        self.assertEqual(len(api.posted("/v1/betaAppReviewSubmissions")), 1)

    def test_a_build_that_never_finishes_processing_fails_and_sends_nothing(self):
        api = FakeAppStoreConnect(["PROCESSING"])
        with self.assertRaisesRegex(subject.ReleaseError, "still PROCESSING"):
            run(api)
        self.assertEqual(api.posts, [])

    def test_a_build_that_failed_processing_fails_at_once(self):
        api = FakeAppStoreConnect(["INVALID"])
        with self.assertRaisesRegex(subject.ReleaseError, "failed processing: INVALID"):
            run(api)
        self.assertEqual(api.posts, [])

    def test_a_build_already_in_review_is_not_submitted_twice(self):
        api = FakeAppStoreConnect(["VALID"], external_state="WAITING_FOR_BETA_REVIEW")
        summary = run(api)
        self.assertEqual(api.posted("/v1/betaAppReviewSubmissions"), [])
        self.assertEqual(len(api.posted("/relationships/betaGroups")), 1)
        self.assertTrue(any("already WAITING_FOR_BETA_REVIEW" in line for line in summary))

    def test_named_groups_limit_where_the_build_goes(self):
        api = FakeAppStoreConnect(
            ["VALID"],
            groups=[("g1", "Public", False), ("g2", "Friends", False), ("g3", "Staff", True)],
        )
        run(api, groups=["Friends"])
        self.assertEqual(api.posted("/relationships/betaGroups"), [{"data": [{"type": "betaGroups", "id": "g2"}]}])

    def test_a_named_group_that_does_not_exist_fails_before_anything_is_sent(self):
        api = FakeAppStoreConnect(["VALID"])
        with self.assertRaisesRegex(subject.ReleaseError, "named Nobody"):
            run(api, groups=["Nobody"])
        self.assertEqual(api.posts, [])

    def test_an_app_without_external_groups_fails(self):
        api = FakeAppStoreConnect(["VALID"], groups=[("g-int", "Staff", True)])
        with self.assertRaisesRegex(subject.ReleaseError, "no external TestFlight group"):
            run(api)

    def test_a_build_missing_export_compliance_is_reported_not_submitted(self):
        api = FakeAppStoreConnect(["VALID"], external_state="MISSING_EXPORT_COMPLIANCE")
        with self.assertRaisesRegex(subject.ReleaseError, "MISSING_EXPORT_COMPLIANCE"):
            run(api)
        self.assertEqual(api.posted("/v1/betaAppReviewSubmissions"), [])


if __name__ == "__main__":
    unittest.main()
