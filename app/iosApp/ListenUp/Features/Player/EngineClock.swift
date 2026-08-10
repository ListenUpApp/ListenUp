import Foundation

/// Pure position math for the engine — kept out of the actor so it is testable
/// without a live `AVQueuePlayer`.
enum EngineClock {
    /// The whole-book position given the current segment and the elapsed time
    /// *within* that segment.
    static func wholeBookPositionMs(
        currentSegmentIndex: Int,
        segments: [AudioSegment],
        segmentElapsedMs: Int64
    ) -> Int64 {
        guard segments.indices.contains(currentSegmentIndex) else { return segmentElapsedMs }
        return segments[currentSegmentIndex].offsetMs + segmentElapsedMs
    }
}
