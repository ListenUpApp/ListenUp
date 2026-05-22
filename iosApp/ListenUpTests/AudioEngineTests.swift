import Foundation
import Testing
@testable import ListenUp

@Suite("EngineClock")
struct AudioEngineTests {
    private let segments = [
        AudioSegment(url: URL(string: "file:///a.m4a")!, durationMs: 1000, offsetMs: 0),
        AudioSegment(url: URL(string: "file:///b.m4a")!, durationMs: 2000, offsetMs: 1000),
    ]

    @Test func wholeBookPositionAddsSegmentOffset() {
        // 400 ms into segment 1 (offset 1000) → whole-book 1400 ms.
        let result = EngineClock.wholeBookPositionMs(
            currentSegmentIndex: 1, segments: segments, segmentElapsedMs: 400
        )
        #expect(result == 1400)
    }

    @Test func wholeBookPositionForFirstSegmentIsElapsed() {
        let result = EngineClock.wholeBookPositionMs(
            currentSegmentIndex: 0, segments: segments, segmentElapsedMs: 250
        )
        #expect(result == 250)
    }

    @Test func wholeBookPositionFallsBackForOutOfRangeIndex() {
        let result = EngineClock.wholeBookPositionMs(
            currentSegmentIndex: 9, segments: segments, segmentElapsedMs: 700
        )
        #expect(result == 700)
    }
}
