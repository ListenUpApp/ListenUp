import Foundation
import ListenupContract
@preconcurrency import Shared

/// Adapts the Koin-resolved `PlaybackPreparer` to `PlaybackPreparing`, mapping the
/// KMP result into native value types.
struct KotlinPlaybackPreparing: PlaybackPreparing {
    let preparer: PlaybackPreparer

    func prepare(bookId: String) async -> PreparedPlayback? {
        // The Kotlin `prepare` returns `PreparedPlayback?` (nullable), logging its own
        // failures and returning `nil`. Swift Export exposes it as `async throws`, so an
        // infra fault (auth/network) can still throw. Split the two so a thrown error is
        // surfaced (no longer silently dropped); type inference avoids naming the bridged
        // Kotlin type, which collides with the native `PreparedPlayback` struct below.
        do {
            guard let prepared = try await preparer.prepare(bookId: BookId(value: bookId)) else {
                return nil
            }
            return PreparedPlayback(
                bookTitle: prepared.bookTitle,
                bookAuthor: prepared.bookAuthor,
                bookNarrator: prepared.bookNarrator,
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
        } catch is CancellationError {
            return nil
        } catch {
            Log.error("PlaybackPreparer.prepare failed for \(bookId)", error: error)
            return nil
        }
    }
}

/// Adapts the Koin-resolved `PlaybackProgressReporter` to `PlaybackProgressReporting`.
/// The reporter fans each signal out to position persistence *and* (on iOS, where a
/// recorder is bound) listening-event recording — so iOS listening history reaches the
/// server. See `PlaybackProgressReporter` in `:sharedLogic`.
struct KotlinProgressReporting: PlaybackProgressReporting {
    let reporter: PlaybackProgressReporter
    func onPlaybackStarted(bookId: String, positionMs: Int64, speed: Float) {
        reporter.onPlaybackStarted(bookId: BookId(value: bookId), positionMs: positionMs, speed: speed)
    }
    func onPlaybackPaused(bookId: String, positionMs: Int64, speed: Float) {
        reporter.onPlaybackPaused(bookId: BookId(value: bookId), positionMs: positionMs, speed: speed)
    }
    func onPositionUpdate(bookId: String, positionMs: Int64, speed: Float) {
        reporter.onPositionUpdate(bookId: BookId(value: bookId), positionMs: positionMs, speed: speed)
    }
    func onSeek(bookId: String, beforeMs: Int64, afterMs: Int64, speed: Float) {
        reporter.onSeek(bookId: BookId(value: bookId), beforeMs: beforeMs, afterMs: afterMs, speed: speed)
    }
    func onSpeedChanged(bookId: String, positionMs: Int64, newSpeed: Float) {
        reporter.onSpeedChanged(bookId: BookId(value: bookId), positionMs: positionMs, newSpeed: newSpeed)
    }
    func onBookFinished(bookId: String, finalPositionMs: Int64) {
        reporter.onBookFinished(bookId: BookId(value: bookId), finalPositionMs: finalPositionMs)
    }
    func savePositionNow(bookId: String, positionMs: Int64) async {
        // The Kotlin suspend fun is exposed as `async throws`; it never throws in
        // practice (failures are logged inside the reporter), so the error is dropped.
        try? await reporter.savePositionNow(bookId: BookId(value: bookId), positionMs: positionMs)
    }
}

/// Bridges the KMP `SleepTimerManager`'s `StateFlow`/`Channel` into native
/// `AsyncStream`s via the existing `FlowBridge` collection mechanism.
@MainActor
final class KotlinSleepTiming: SleepTiming {
    private let manager: SleepTimerManager
    private let bridge: FlowBridge
    let stateStream: AsyncStream<SleepTimingState>
    private let stateContinuation: AsyncStream<SleepTimingState>.Continuation
    let fired: AsyncStream<Void>
    private let firedContinuation: AsyncStream<Void>.Continuation

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

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

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
    func setEndOfChapterTimer() { manager.setTimer(mode: SleepTimerModeEndOfChapter.shared) }
    func cancelTimer() { manager.cancelTimer() }
    func onChapterChanged(newChapterIndex: Int) {
        manager.onChapterChanged(newChapterIndex: Int32(newChapterIndex))
    }
    func onFadeCompleted() { manager.onFadeCompleted() }
}

/// Bridges `PlaybackPreferences`' reactive skip-interval `Flow<Int>`s into native
/// `AsyncStream<Int>`s. The shared store emits the current value on first collect and
/// re-emits on every write (from any surface), so the player tracks the setting live.
@MainActor
final class KotlinSkipIntervalProviding: SkipIntervalProviding {
    private let bridge: FlowBridge
    let forwardSeconds: AsyncStream<Int>
    private let forwardContinuation: AsyncStream<Int>.Continuation
    let backwardSeconds: AsyncStream<Int>
    private let backwardContinuation: AsyncStream<Int>.Continuation

    init(preferences: PlaybackPreferences) {
        self.bridge = FlowBridge()
        var fc: AsyncStream<Int>.Continuation!
        forwardSeconds = AsyncStream { fc = $0 }; forwardContinuation = fc
        var bc: AsyncStream<Int>.Continuation!
        backwardSeconds = AsyncStream { bc = $0 }; backwardContinuation = bc
        // A cold Kotlin `Flow` is exposed over Swift Export via `asAsyncSequence()` (the
        // `observeDocuments` precedent), as a `KotlinFlowSequence<Int32>`. The explicit
        // `Int32` annotation steers `bind` to its `AsyncSequence` overload; convert to `Int`.
        let forward = preferences.observeDefaultSkipForwardSec().asAsyncSequence()
        bridge.bind(forward) { [forwardContinuation] (seconds: Int32) in
            forwardContinuation.yield(Int(seconds))
        }
        let backward = preferences.observeDefaultSkipBackwardSec().asAsyncSequence()
        bridge.bind(backward) { [backwardContinuation] (seconds: Int32) in
            backwardContinuation.yield(Int(seconds))
        }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.
}

struct KotlinBookCoverProviding: BookCoverProviding {
    let repository: BookRepository
    func coverBlurHash(bookId: String) async -> String? {
        (try? await repository.getBookListItem(id: bookId))?.coverBlurHash
    }
}

/// Adapts `DocumentRepository` to `BookDocumentProviding`. Takes the first emission from
/// `observeDocuments` (a one-shot read) and calls `ensureLocalPathOrNull` to download on demand.
struct KotlinBookDocumentProviding: BookDocumentProviding {
    let repository: DocumentRepository

    func firstPdfDocId(bookId: String) async -> String? {
        // `observeDocuments` is a Kotlin Flow; Swift Export exposes it as an AsyncSequence
        // via `asAsyncSequence()`. Take the first emission — the Room store reflects the
        // last sync, so one read is sufficient here (the coordinator re-reads on each book load).
        let docs: [BookDocument]
        do {
            // No emission (no docs yet) → nil → empty list, distinct from a thrown query error.
            docs = try await repository.observeDocuments(bookId: BookId(value: bookId))
                .asAsyncSequence().first(where: { _ in true }) ?? []
        } catch is CancellationError {
            return nil
        } catch {
            // A thrown Flow/DB error is no longer conflated with "no PDF" — surface why.
            Log.warning("observeDocuments failed for \(bookId): \(error.localizedDescription)")
            return nil
        }
        // `BookDocument.format` is a plain Kotlin String; Swift Export exposes it directly.
        return docs.first(where: { $0.format.lowercased() == "pdf" })?.id
    }

    func ensureLocalPath(bookId: String, docId: String) async -> String? {
        // iOS-safe accessor: AppResult is folded in Kotlin. A failure (or thrown infra fault) → nil.
        // `try?` already flattens the bridged `String?` to a single optional, so no `?? nil` is needed.
        try? await repository.ensureLocalPathOrNull(bookId: BookId(value: bookId), docId: docId)
    }
}
