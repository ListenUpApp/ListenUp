import Foundation
@preconcurrency import Shared

/// Engine event handling (position ticks, buffering/ready transitions, completion, failure) and the
/// playback-preempts-downloads bandwidth signal. Split out of `PlayerCoordinator.swift`.
@MainActor
extension PlayerCoordinator {
    func handleEngineEvent(_ event: AudioEngineEvent) {
        // Drop any engine event that arrives while a book is loading: it belongs to the outgoing
        // book or the muted preroll. Critically this covers `.failed` — a late failure from book A
        // must not abort book B's start. B's own load failure surfaces via `engine.load` returning false.
        if isEngineLoading { return }
        // Nothing is loaded — a late event belongs to the book we just unloaded. Drop it.
        guard let loaded = engineBook else { return }
        switch event {
        case .position(let ms, let rate):
            // A positive-rate sample IS "audio is advancing" — promote a lingering `.buffering` whose
            // `.ready` KVO transition was swallowed by the loading gate.
            if rate > 0, isBuffering {
                enginePhase = .playing
                updateNowPlaying()
            }
            positionTracker.update(positionMs: ms, rate: rate)
            refreshChapterIndex()
            facts.position(ms: ms, generation: factGeneration)
            reportPositionIfNeeded(ms)
            if chapterIndex != lastSyncedChapterIndex {
                lastSyncedChapterIndex = chapterIndex
                // Re-stamp the lock screen at the boundary: its pushed duration/elapsed are scoped to the
                // CURRENT chapter, and the info center would otherwise extrapolate past the old window.
                updateNowPlaying()
            }
        case .statusChanged(let status):
            applyEngineStatus(status)
        case .ended:
            progress.onBookFinished(bookId: loaded.bookId, positionMs: bookPositionMs, durationMs: bookDurationMs)
            enginePhase = .ended
            updateNowPlaying()
        case .failed(let failure):
            // The book played: record its place before letting go of it, as a pause would.
            recordPlaceBeforeDropping(loaded.bookId)
            fail(failure)
            stopPlayerBehindFailure()
        }
    }

    /// Nothing plays behind an error. A failed item leaves the player's rate up, and AVPlayer resumes a stalled
    /// queue, or moves on to the next segment, by itself once the network returns. Stop it, unless a newer load
    /// already owns the player: only Retry or an explicit Play, which prepare the book afresh, start it again.
    private func stopPlayerBehindFailure() {
        let generation = loadGeneration
        Task {
            guard !isSuperseded(generation) else { return }
            await engine.pause()
        }
    }

    /// Collapse the engine's interleaved ready/buffering stream into phase transitions.
    /// `.ready` promotes a buffering book to playing; `.buffering` demotes a playing book.
    /// A paused player ignores status churn.
    private func applyEngineStatus(_ status: AudioEngineStatus) {
        switch status {
        case .buffering:
            if isPlaying {
                enginePhase = .buffering
                updateNowPlaying()
            }
        case .ready:
            if isBuffering {
                enginePhase = .playing
                updateNowPlaying()
            }
        case .idle:
            break
        }
    }

    private func reportPositionIfNeeded(_ positionMs: Int64) {
        guard let id = engineBook?.bookId else { return }
        if abs(positionMs - lastReportedPositionMs) >= Self.positionReportIntervalMs {
            lastReportedPositionMs = positionMs
            progress.onPositionUpdate(bookId: id, positionMs: positionMs, speed: playbackSpeed)
        }
    }

    /// A book the engine is about to drop mid-play: record the pause (if it was playing) and save the exact place,
    /// as `ManagedPlayerEngine.stop()` does, so a Retry resumes where the audio stopped.
    private func recordPlaceBeforeDropping(_ bookId: String) {
        if isPlaybackActive { reportPaused(bookId) }
        let positionMs = bookPositionMs
        let durationMs = bookDurationMs
        Task { await progress.savePositionNow(bookId: bookId, positionMs: positionMs, durationMs: durationMs) }
    }

    // The shared tracker decides whether a pause or an end finishes the book: it needs both numbers.
    func reportPaused(_ bookId: String) {
        progress.onPlaybackPaused(
            bookId: bookId, positionMs: bookPositionMs, speed: playbackSpeed, durationMs: bookDurationMs)
    }

    /// Tell the shared coordinator whether a STREAMING book is currently buffering, so background
    /// downloads yield only during the stalls that matter (a local book never asks them to).
    func updateBandwidthSignal() {
        bandwidthCoordinator?.setStreamingBuffering(active: isBuffering && isCurrentBookStreaming)
    }
}
