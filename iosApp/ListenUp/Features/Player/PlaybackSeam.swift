import Foundation

/// The audio transport the coordinator drives. `AudioEngine` is the production
/// conformer; `FakePlaybackEngine` the test double. Platform-neutral — no
/// AVFoundation types cross this boundary, so it ports to macOS/watchOS as-is.
protocol PlaybackEngine: Sendable {
    /// The engine's event stream. Created once at init; consumed once.
    nonisolated var events: AsyncStream<AudioEngineEvent> { get }
    func load(segments: [AudioSegment], startPositionMs: Int64) async
    func play() async
    func pause() async
    func seek(toMs positionMs: Int64) async
    func setRate(_ newRate: Float) async
    /// Linear output gain 0.0...1.0. Used for the sleep-timer fade-out.
    func setVolume(_ volume: Float) async
    /// Deactivate the shared audio session so other apps' audio can resume.
    func deactivateSession() async
    func release() async
}

// `@preconcurrency` because `Chapter_` (a SKIE-bridged KMP class) is not
// `Sendable`; this snapshot is built and consumed on the main actor, so treating
// the Shared-module `Sendable` gap as a warning is sound here.
@preconcurrency import Shared

/// Native, platform-neutral snapshot of a prepared book. The Kotlin adapter maps
/// the shared preparer's result into this so the protocol never leaks KMP types.
struct PreparedPlayback: Sendable {
    let bookTitle: String
    let bookAuthor: String
    let coverPath: String?
    let resumeSpeed: Float
    let resumePositionMs: Int64
    let chapters: [Chapter_]
    let timeline: PreparedTimeline
}

struct PreparedTimeline: Sendable {
    let totalDurationMs: Int64
    let files: [PreparedFile]
}

struct PreparedFile: Sendable {
    let localPath: String?
    let streamingUrl: String
    let durationMs: Int64
    let startOffsetMs: Int64
}

/// Resolve + map a book to a `PreparedPlayback`, or `nil` on failure.
protocol PlaybackPreparing: Sendable {
    func prepare(bookId: String) async -> PreparedPlayback?
}

/// Position + listening-event persistence. `bookId` is the value-class-erased id.
protocol PlaybackProgressReporting: Sendable {
    func onPlaybackStarted(bookId: String, positionMs: Int64, speed: Float)
    func onPlaybackPaused(bookId: String, positionMs: Int64, speed: Float)
    func onPositionUpdate(bookId: String, positionMs: Int64, speed: Float)
    func onSpeedChanged(bookId: String, positionMs: Int64, newSpeed: Float)
    func onBookFinished(bookId: String, finalPositionMs: Int64)
    /// Blocking critical save — used on pause, seek, and app backgrounding.
    func savePositionNow(bookId: String, positionMs: Int64) async
}

/// The sleep timer, projected as native streams. `stateStream` mirrors the KMP
/// `state`; `fired` emits when the timer reaches zero and the consumer must fade+pause.
protocol SleepTiming: Sendable {
    var stateStream: AsyncStream<SleepTimingState> { get }
    var fired: AsyncStream<Void> { get }
    func setDurationTimer(minutes: Int)
    func setEndOfChapterTimer()
    func cancelTimer()
    func onChapterChanged(newChapterIndex: Int)
    func onFadeCompleted()
}

/// Native projection of the KMP sleep-timer state for the UI surface.
enum SleepTimingState: Sendable, Equatable {
    case inactive
    case active(remainingMs: Int64, isEndOfChapter: Bool, label: String)
}

protocol BookCoverProviding: Sendable {
    func coverBlurHash(bookId: String) async -> String?
}
