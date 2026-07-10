import Foundation
import Testing
@testable import ListenUp

@Suite("EngineClock")
struct AudioEngineTests {
    private let segments = [
        AudioSegment(url: URL(string: "file:///a.m4a")!, durationMs: 1000, offsetMs: 0),
        AudioSegment(url: URL(string: "file:///b.m4a")!, durationMs: 2000, offsetMs: 1000)
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

@Suite("AudioEngine release guard")
struct AudioEngineReleaseTests {
    /// `load()` after `release()` must fail FAST (the `isReleased` guard). Without it, the ordered
    /// drain loop is dead, so the readiness wait's only resumer is gone and `load` would hang the
    /// full 20s timeout. Racing against a 2s bound: `false` = guarded (correct); `nil` = it hung.
    @Test func loadAfterReleaseFailsFastNotHang() async {
        let engine = AudioEngine()
        await engine.release()
        let segment = AudioSegment(url: URL(string: "file:///nope.m4a")!, durationMs: 1000, offsetMs: 0)

        let result: Bool? = await withTaskGroup(of: Bool?.self) { group in
            group.addTask { await engine.load(segments: [segment], startPositionMs: 0) }
            group.addTask { try? await Task.sleep(for: .seconds(2)); return nil }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }

        #expect(result == false)
    }
}
