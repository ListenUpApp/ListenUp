import Testing
@testable import ListenUp

/// Critical-path coverage for the audiobook promise "never lose the user's place"
/// (iOS charter rule 13) and the player's uncovered error states.
///
/// The save-on-pause / save-on-seek / save-on-background machinery is wired but lightly tested;
/// `PlayerCoordinatorTests` already covers save-when-visible, save-no-op-when-idle, and
/// seek-reports-position, and `ScenePhasePolicyTests` covers the background-save decision. This
/// file fills the remaining gaps: pause carries the reached position, and the two preparation
/// failure modes surface their error phase.

@Suite("Pause persistence")
@MainActor
struct PausePersistenceTests {
    /// Pausing reports the user's current place. Pause is one of the three save triggers and
    /// must carry the position that was reached — a refactor that drops the position here would
    /// silently re-introduce the exact "lost on pause" regression rule 13 exists to prevent.
    @Test func pauseReportsCurrentPosition() async throws {
        let engine = FakePlaybackEngine()
        let progress = FakeProgressReporting()
        let preparer = FakePlaybackPreparing()
        preparer.result = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: nil, resumeSpeed: 1.0,
            resumePositionMs: 0, chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 60000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 60000, startOffsetMs: 0)])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: FakeSleepTiming(),
            engine: engine)
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        // Anchor the position so the pause report carries a concrete, non-zero place.
        // `rate: 0` (paused sample) holds the position exactly; a positive rate would start
        // the CADisplayLink and interpolate past 12345, racing the `== 12345` poll into a hang.
        engine.emit(.position(ms: 12345, rate: 0.0))
        await awaitUntil { coordinator.bookPositionMs == 12345 }

        coordinator.togglePlayback()
        await engine.waitUntilPaused()

        // Poll for the pause report rather than asserting it the instant `pause()` lands — defensive
        // against any late/reordered delivery under the parallel-suite MainActor scheduler. On a
        // genuinely-wrong position this still fails (the value never arrives), and the failure
        // message surfaces what WAS recorded so a CI flake here is finally diagnosable, not opaque.
        await awaitUntil { progress.pausedCalls.contains { $0.0 == "book1" && $0.1 == 12345 } }
        #expect(
            progress.pausedCalls.contains { $0.0 == "book1" && $0.1 == 12345 },
            "expected a pause report at 12345 for book1; recorded: \(progress.pausedCalls)",
        )
    }
}

@Suite("Prepare error states")
@MainActor
struct PrepareErrorStateTests {
    /// The preparer returns `nil` (the book couldn't be prepared) → the coordinator surfaces
    /// the "Couldn't start playback." error phase rather than silently staying `.preparing`.
    @Test func nilPreparedYieldsCouldNotStartError() async {
        let preparer = FakePlaybackPreparing()
        preparer.result = nil
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: FakeProgressReporting(), sleep: FakeSleepTiming(),
            engine: FakePlaybackEngine())

        coordinator.play(bookId: "book1")
        await awaitUntil {
            if case .error = coordinator.phase { return true }
            return false
        }

        guard case let .error(state) = coordinator.phase else {
            Issue.record("expected .error, got \(coordinator.phase)")
            return
        }
        #expect(state.message == "Couldn't start playback.")
    }

    /// A prepared book whose timeline yields no playable segments (no local path and an empty
    /// streaming URL) → the "This book has no audio." error phase.
    @Test func emptyTimelineYieldsNoAudioError() async {
        let preparer = FakePlaybackPreparing()
        preparer.result = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: nil, resumeSpeed: 1.0,
            resumePositionMs: 0, chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 60000, files: [
                PreparedFile(localPath: nil, streamingUrl: "", durationMs: 60000, startOffsetMs: 0)])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: FakeProgressReporting(), sleep: FakeSleepTiming(),
            engine: FakePlaybackEngine())

        coordinator.play(bookId: "book1")
        await awaitUntil {
            if case .error = coordinator.phase { return true }
            return false
        }

        guard case let .error(state) = coordinator.phase else {
            Issue.record("expected .error, got \(coordinator.phase)")
            return
        }
        #expect(state.message == "This book has no audio.")
    }
}
