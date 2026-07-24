import Foundation
import Testing
@testable import ListenUp

@Suite("AudioSegment & SegmentMath")
struct AudioSegmentTests {
    private let segments = [
        AudioSegment(url: URL(string: "file:///a.m4a")!, durationMs: 1000, offsetMs: 0),
        AudioSegment(url: URL(string: "file:///b.m4a")!, durationMs: 2000, offsetMs: 1000),
        AudioSegment(url: URL(string: "file:///c.m4a")!, durationMs: 500, offsetMs: 3000)
    ]

    @Test func endOffsetIsOffsetPlusDuration() {
        #expect(segments[1].endOffsetMs == 3000)
    }

    @Test func segmentIndexFindsContainingSegment() {
        #expect(SegmentMath.segmentIndex(forPositionMs: 0, in: segments) == 0)
        #expect(SegmentMath.segmentIndex(forPositionMs: 999, in: segments) == 0)
        #expect(SegmentMath.segmentIndex(forPositionMs: 1000, in: segments) == 1)
        #expect(SegmentMath.segmentIndex(forPositionMs: 2999, in: segments) == 1)
        #expect(SegmentMath.segmentIndex(forPositionMs: 3000, in: segments) == 2)
    }

    @Test func segmentIndexClampsPastEndToLastSegment() {
        #expect(SegmentMath.segmentIndex(forPositionMs: 99_999, in: segments) == 2)
    }

    @Test func segmentIndexIsNilForEmpty() {
        #expect(SegmentMath.segmentIndex(forPositionMs: 0, in: []) == nil)
    }
}
