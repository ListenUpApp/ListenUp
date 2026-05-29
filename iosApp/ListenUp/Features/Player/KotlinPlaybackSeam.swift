import Foundation
@preconcurrency import Shared

/// Adapts the Koin-resolved `PlaybackPreparer` to `PlaybackPreparing`, mapping the
/// KMP result into native value types.
struct KotlinPlaybackPreparing: PlaybackPreparing {
    let preparer: PlaybackPreparer

    func prepare(bookId: String) async -> PreparedPlayback? {
        guard let prepared = try? await preparer.prepare(
            bookId: bookId, onPrepareProgress: { @Sendable _ in }
        ) else { return nil }
        return PreparedPlayback(
            bookTitle: prepared.bookTitle,
            bookAuthor: prepared.bookAuthor,
            coverPath: prepared.coverPath,
            resumeSpeed: prepared.resumeSpeed,
            resumePositionMs: prepared.resumePositionMs,
            chapters: Array(prepared.chapters),
            timeline: PreparedTimeline(
                totalDurationMs: prepared.timeline.totalDurationMs,
                files: prepared.timeline.files.map { file in
                    PreparedFile(
                        localPath: file.localPath,
                        streamingUrl: file.streamingUrl,
                        durationMs: file.durationMs,
                        startOffsetMs: file.startOffsetMs
                    )
                }
            )
        )
    }
}

struct KotlinProgressReporting: PlaybackProgressReporting {
    let tracker: ProgressTracker
    func onPlaybackStarted(bookId: String, positionMs: Int64, speed: Float) {
        tracker.onPlaybackStarted(bookId: bookId, positionMs: positionMs, speed: speed)
    }
    func onPlaybackPaused(bookId: String, positionMs: Int64, speed: Float) {
        tracker.onPlaybackPaused(bookId: bookId, positionMs: positionMs, speed: speed)
    }
    func onPositionUpdate(bookId: String, positionMs: Int64, speed: Float) {
        tracker.onPositionUpdate(bookId: bookId, positionMs: positionMs, speed: speed)
    }
    func onSpeedChanged(bookId: String, positionMs: Int64, newSpeed: Float) {
        tracker.onSpeedChanged(bookId: bookId, positionMs: positionMs, newSpeed: newSpeed)
    }
    func onBookFinished(bookId: String, finalPositionMs: Int64) {
        tracker.onBookFinished(bookId: bookId, finalPositionMs: finalPositionMs)
    }
    func savePositionNow(bookId: String, positionMs: Int64) async {
        // SKIE bridges the Kotlin suspend fun as `async throws`; it never throws in
        // practice (failures are logged inside the tracker), so the error is dropped.
        try? await tracker.savePositionNow(bookId: bookId, positionMs: positionMs)
    }
}

/// Bridges the KMP `SleepTimerManager`'s `StateFlow`/`Channel` into native
/// `AsyncStream`s via the existing `FlowBridge` collection mechanism.
final class KotlinSleepTiming: SleepTiming, @unchecked Sendable {
    private let manager: SleepTimerManager
    private let bridge: FlowBridge
    let stateStream: AsyncStream<SleepTimingState>
    private let stateContinuation: AsyncStream<SleepTimingState>.Continuation
    let fired: AsyncStream<Void>
    private let firedContinuation: AsyncStream<Void>.Continuation

    // `@MainActor`: the only construction site is `Dependencies.playerCoordinator`
    // (main-actor), and `FlowBridge` is main-actor-isolated. The protocol's
    // requirements stay nonisolated; only construction is pinned to the main actor.
    @MainActor
    init(manager: SleepTimerManager) {
        self.manager = manager
        self.bridge = FlowBridge()
        var sc: AsyncStream<SleepTimingState>.Continuation!
        stateStream = AsyncStream { sc = $0 }; stateContinuation = sc
        var fc: AsyncStream<Void>.Continuation!
        fired = AsyncStream { fc = $0 }; firedContinuation = fc
        bridge.bind(manager.state) { [stateContinuation] state in
            stateContinuation.yield(Self.map(state))
        }
        bridge.bind(manager.sleepEvent) { [firedContinuation] _ in
            firedContinuation.yield(())
        }
    }

    private static func map(_ state: SleepTimerState) -> SleepTimingState {
        guard let active = state as? SleepTimerStateActive else { return .inactive }
        let isEoc = active.mode is SleepTimerModeEndOfChapter
        return .active(
            remainingMs: active.remainingMs,
            isEndOfChapter: isEoc,
            label: isEoc ? "End of chapter" : active.formatRemaining()
        )
    }

    func setDurationTimer(minutes: Int) {
        manager.setTimer(mode: SleepTimerModeDuration(minutes: Int32(minutes)))
    }
    func setEndOfChapterTimer() { manager.setTimer(mode: SleepTimerModeEndOfChapter()) }
    func cancelTimer() { manager.cancelTimer() }
    func onChapterChanged(newChapterIndex: Int) {
        manager.onChapterChanged(newChapterIndex: Int32(newChapterIndex))
    }
    func onFadeCompleted() { manager.onFadeCompleted() }
}

struct KotlinBookCoverProviding: BookCoverProviding {
    let repository: BookRepository
    func coverBlurHash(bookId: String) async -> String? {
        (try? await repository.getBookListItem(id: bookId))?.coverBlurHash
    }
}
