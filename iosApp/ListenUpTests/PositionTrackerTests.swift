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
