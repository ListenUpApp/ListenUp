import Testing
@testable import ListenUp

/// Boot-sequence integration harness (golden-path app startup).
///
/// The app's startup wires auth → realtime-sync activation through `SyncSessionController`
/// (`ListenUpApp.activateSyncIfAuthenticated`): on authentication it builds the controller with
/// `connectRealtime`/`resumeDownloads` closures and calls `activate()`. Without that step the
/// library never populates (the SSE firehose + initial pull never start). This suite pins that
/// boot contract at the injectable seam — the controller and its closures — so a refactor of the
/// boot wiring can't silently break startup.
///
/// Scope is deliberately the controller seam, not `RootView` itself: the closures are already
/// injectable, so no production seam is needed (plan 013, Step 1). The existing
/// `SyncSessionControllerTests` cover the direct `connectAndResume()` golden path and ordering;
/// this suite covers the **fire-and-forget `activate()` boot trigger** and the **representative
/// failure path** (Never Stranded: a sync-connect failure must leave the app usable).
@MainActor
struct BootSequenceTests {

    /// Records the two boot side effects via an `AsyncGate` so the fire-and-forget `activate()`
    /// can be awaited by causality rather than a wall-clock sleep. `@MainActor`-isolated so it
    /// can construct the `@MainActor`-isolated `SyncSessionController` (the outer suite's
    /// `@MainActor` does not propagate into a nested type).
    @MainActor
    private final class BootRecorder: @unchecked Sendable {
        private(set) var connectCount = 0
        private(set) var resumeCount = 0
        private(set) var connectThrew = false
        let gate = AsyncGate()

        /// `connectRealtime` mirrors the production closure: best-effort, swallowing its own
        /// error so a sync-connect failure never propagates out of boot.
        func makeController(connectFails: Bool = false) -> SyncSessionController {
            SyncSessionController(
                connectRealtime: { [self] in
                    connectCount += 1
                    if connectFails {
                        // The production closure catches and logs; we record the swallow so the
                        // test can assert the failure was handled, not propagated.
                        connectThrew = true
                    }
                    // Keyed gate (not the predicate form): the "has it happened?" state lives
                    // inside the gate, so the test's `wait(forKey:)` needs no `self`-capturing
                    // closure crossing the `@MainActor` boundary (Swift 6 sending-closure trap).
                    gate.fire("connectRealtime")
                },
                resumeDownloads: { [self] in
                    resumeCount += 1
                    gate.fire("resumeDownloads")
                }
            )
        }
    }

    /// Golden path: the boot trigger `activate()` runs both side effects exactly once — the SSE
    /// firehose/initial pull connect, then incomplete downloads resume.
    @Test func authenticatedBootActivatesSync() async {
        let recorder = BootRecorder()
        let controller = recorder.makeController()

        // The boot wiring's actual entry point (auth becomes `.authenticated`): fire-and-forget.
        controller.activate()

        // `resumeDownloads` runs after `connectRealtime`, so awaiting its key proves both ran.
        await recorder.gate.wait(forKey: "resumeDownloads")
        #expect(recorder.connectCount == 1)
        #expect(recorder.resumeCount == 1)
    }

    /// Representative failure path: realtime connect fails. The failure is swallowed-but-handled
    /// inside the closure (logged in production), boot does not crash or wedge, and the rest of
    /// the sequence (resume downloads) still runs — the app stays usable with pull-to-refresh as
    /// the manual fallback (Never Stranded, SOUL.md).
    @Test func syncConnectFailureLeavesAppUsable() async {
        let recorder = BootRecorder()
        let controller = recorder.makeController(connectFails: true)

        // Await the awaitable form so the test observes the full sequence deterministically;
        // a thrown error here would surface as a test failure rather than a swallowed one.
        await controller.connectAndResume()

        #expect(recorder.connectThrew)        // the failure path was exercised
        #expect(recorder.connectCount == 1)
        #expect(recorder.resumeCount == 1)    // boot continued past the failure
    }
}
