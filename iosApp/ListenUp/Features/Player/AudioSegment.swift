import Foundation

/// One playable audio segment, resolved for the engine. The coordinator builds
/// these from the KMP timeline — picking the local file when downloaded, the
/// remote URL otherwise — so `AudioEngine` never sees a KMP type.
struct AudioSegment: Equatable, Sendable {
    /// The resolved playable URL (a `file://` URL when downloaded, else remote).
    let url: URL
    /// This segment's duration in milliseconds.
    let durationMs: Int64
    /// This segment's start offset within the whole-book timeline, in milliseconds.
    let offsetMs: Int64

    /// The whole-book position (ms) at which this segment ends.
    var endOffsetMs: Int64 { offsetMs + durationMs }
}

/// An event emitted by `AudioEngine` on its single output stream. One stream,
/// one consumer (`PlayerCoordinator`), carrying both position and lifecycle.
enum AudioEngineEvent: Sendable, Equatable {
    /// A real position sample — whole-book position and the current rate
    /// (0 when paused, the playback speed when playing).
    case position(ms: Int64, rate: Double)
    /// The engine's readiness changed.
    case statusChanged(AudioEngineStatus)
    /// The final segment played to its end.
    case ended
    /// Playback failed; `message` is user-facing.
    case failed(message: String)
}

/// `AudioEngine` readiness.
enum AudioEngineStatus: Sendable, Equatable {
    /// No item loaded.
    case idle
    /// An item is loaded but stalled, waiting on data.
    case buffering
    /// An item is loaded and playable.
    case ready
}

/// Resolves a whole-book position to the segment that contains it.
enum SegmentMath {
    /// Returns the index of the segment containing `positionMs`, or `nil` if
    /// `segments` is empty. A position past the end clamps to the last segment.
    static func segmentIndex(forPositionMs positionMs: Int64, in segments: [AudioSegment]) -> Int? {
        guard !segments.isEmpty else { return nil }
        for (index, segment) in segments.enumerated() where positionMs < segment.endOffsetMs {
            return index
        }
        return segments.count - 1
    }
}
