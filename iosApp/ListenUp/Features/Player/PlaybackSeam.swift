import Foundation

/// The audio transport the coordinator drives. `AudioEngine` is the production
/// conformer; `FakePlaybackEngine` the test double. Platform-neutral — no
/// AVFoundation types cross this boundary, so it ports to macOS/watchOS as-is.
protocol PlaybackEngine: Sendable {
    /// The engine's event stream. Created once at init; consumed once.
    nonisolated var events: AsyncStream<AudioEngineEvent> { get }
    /// Configure the session, enqueue `segments`, and seek to `startPositionMs` before
    /// returning. Returns `true` once the first item is ready and positioned; `false` on a
    /// configuration/queue failure or readiness timeout (a `.failed` event is emitted on
    /// those paths). The caller starts playback with `play()` only after a `true` result.
    func load(segments: [AudioSegment], startPositionMs: Int64) async -> Bool
    func play() async
    func pause() async
    func seek(toMs positionMs: Int64) async
    func setRate(_ newRate: Float) async
    /// Linear output gain 0.0...1.0. Used for the sleep-timer fade-out.
    func setVolume(_ volume: Float) async
    /// Deactivate the shared audio session so other apps' audio can resume.
    func deactivateSession() async
    /// Re-assert the shared audio session as active. Called before resuming
    /// after an interruption — some interruptions deactivate the session, and
    /// resuming into a dead session plays silence.
    func activateSession() async
    func release() async
}

// `@preconcurrency` because `Chapter` (a Swift Export-bridged KMP class) is not
// `Sendable`; this snapshot is built and consumed on the main actor, so treating
// the Shared-module `Sendable` gap as a warning is sound here.
@preconcurrency import Shared

/// Native, platform-neutral snapshot of a prepared book. The Kotlin adapter maps
/// the shared preparer's result into this so the protocol never leaks KMP types.
struct PreparedPlayback: Sendable {
    let bookTitle: String
    let bookAuthor: String
    /// Comma-joined narrator(s); empty when the book has no narrator.
    let bookNarrator: String
    let coverPath: String?
    let resumeSpeed: Float
    let resumePositionMs: Int64
    let chapters: [Chapter]
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
    /// A seek splits the listening span so the jumped-over range isn't counted as listened.
    func onSeek(bookId: String, beforeMs: Int64, afterMs: Int64, speed: Float)
    func onSpeedChanged(bookId: String, positionMs: Int64, newSpeed: Float)
    func onBookFinished(bookId: String, finalPositionMs: Int64)
    /// Blocking critical save — used on pause, seek, and app backgrounding.
    func savePositionNow(bookId: String, positionMs: Int64) async
}

/// The sleep timer, projected as native streams. `stateStream` mirrors the KMP
/// `state`; `fired` emits when the timer reaches zero and the consumer must fade+pause.
///
/// `@MainActor`-isolated: the conformer holds a non-`Sendable` Kotlin manager and a
/// main-actor `FlowBridge`, and the sole consumer (`PlayerCoordinator`) is `@MainActor`.
/// Isolation lets the type system enforce that invariant instead of an `@unchecked` comment.
@MainActor
protocol SleepTiming {
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

/// The user's skip-interval settings, projected as native streams. Each stream emits
/// the current value immediately and re-emits whenever the setting is written from any
/// surface (e.g. the Settings screen) — so a change lands on the player live, mid-session.
///
/// `@MainActor`-isolated: the conformer holds a main-actor `FlowBridge` and the sole
/// consumer (`PlayerCoordinator`) is `@MainActor` — isolation enforces it, no `@unchecked`.
@MainActor
protocol SkipIntervalProviding {
    /// Forward skip interval in seconds. Emits current value, then on every change.
    var forwardSeconds: AsyncStream<Int> { get }
    /// Backward skip interval in seconds. Emits current value, then on every change.
    var backwardSeconds: AsyncStream<Int> { get }
}

protocol BookCoverProviding: Sendable {
    func coverBlurHash(bookId: String) async -> String?
}

/// Provides supplementary document metadata and on-demand local paths for a book.
/// Used by `PlayerCoordinator` to show the "Open PDF" menu item and to download
/// the file before handing it to `DocumentReaderView`.
protocol BookDocumentProviding: Sendable {
    /// Returns the id of the first PDF document for the given book, or `nil` if none.
    func firstPdfDocId(bookId: String) async -> String?
    /// Downloads the document if absent and returns its absolute local path, or `nil` on failure.
    func ensureLocalPath(bookId: String, docId: String) async -> String?
}

/// No-op conformer used as the default for tests that don't inject a real provider.
struct NoDocumentProviding: BookDocumentProviding {
    func firstPdfDocId(bookId: String) async -> String? { nil }
    func ensureLocalPath(bookId: String, docId: String) async -> String? { nil }
}
