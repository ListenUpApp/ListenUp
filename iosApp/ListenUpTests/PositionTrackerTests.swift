import Foundation
import Testing
@testable import ListenUp

@Suite("PositionInterpolator")
struct PositionTrackerTests {
    @Test func pausedRateReturnsSampleUnchanged() {
        let result = PositionInterpolator.interpolate(
            sampleMs: 5000, sampleTimestamp: 100, rate: 0, now: 105
        )
        #expect(result == 5000)
    }

    @Test func realtimeRateAdvancesOneSecondPerSecond() {
        // 1.0x: 2s of wall time → +2000 ms of audio.
        let result = PositionInterpolator.interpolate(
            sampleMs: 5000, sampleTimestamp: 100, rate: 1.0, now: 102
        )
        #expect(result == 7000)
    }

    @Test func fasterRateAdvancesProportionally() {
        // 1.5x: 2s of wall time → +3000 ms of audio.
        let result = PositionInterpolator.interpolate(
            sampleMs: 0, sampleTimestamp: 0, rate: 1.5, now: 2
        )
        #expect(result == 3000)
    }

    @Test func clockSkewBackwardsDoesNotRewind() {
        // now < sampleTimestamp → elapsed clamps to 0, no rewind.
        let result = PositionInterpolator.interpolate(
            sampleMs: 5000, sampleTimestamp: 100, rate: 1.0, now: 95
        )
        #expect(result == 5000)
    }
}

@Suite("PositionTracker reset & seed")
@MainActor
struct PositionTrackerResetTests {
    /// Seeding with a paused sample (rate 0) surfaces the resume position immediately, so the
    /// UI shows the right chapter/time before the engine's first real sample (RC-2). Rate 0 is
    /// deliberate: it holds the value exactly, without starting the interpolating display link.
    @Test func seedSurfacesResumePosition() {
        let tracker = PositionTracker()
        tracker.update(positionMs: 42_000, rate: 0)
        #expect(tracker.positionMs == 42_000)
        #expect(tracker.displayPositionMs == 42_000)
    }

    /// Reset zeroes the position — the switch path relies on this to clear the outgoing book's
    /// place before the incoming book is seeded.
    @Test func resetZeroesPosition() {
        let tracker = PositionTracker()
        tracker.update(positionMs: 42_000, rate: 0)
        tracker.reset()
        #expect(tracker.positionMs == 0)
        #expect(tracker.displayPositionMs == 0)
    }
}

@Suite("PositionQuantizer")
struct PositionQuantizerTests {
    @Test func floorsToWholeSeconds() {
        #expect(PositionQuantizer.displayMs(0) == 0)
        #expect(PositionQuantizer.displayMs(1_200) == 1_000)
        #expect(PositionQuantizer.displayMs(1_999) == 1_000)
        #expect(PositionQuantizer.displayMs(2_000) == 2_000)
    }

    @Test func isStableWithinASecondAndStepsOnBoundary() {
        // Same second → same bucket; crossing the boundary → a new bucket.
        #expect(PositionQuantizer.displayMs(1_001) == PositionQuantizer.displayMs(1_500))
        #expect(PositionQuantizer.displayMs(1_999) != PositionQuantizer.displayMs(2_000))
    }
}
