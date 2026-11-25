import Foundation
@preconcurrency import Shared

/// Ordered instruction execution (`load`, `play`, `pause`, `seek`, `setRate`, `stop`) plus load-epoch
/// superseding and uncancellable teardown persistence. Split out of `PlayerCoordinator.swift`.
extension PlayerCoordinator {
    /// Execute one instruction from the shared player. Awaited by the drain task, so instructions
    /// reach the engine in the order they were issued; a `.load` only starts its load task and
    /// returns, so a later `.load` supersedes it and a `.stop` cancels it. The book starts later, as
    /// its own step in the same queue (`startLoaded`).
    func execute(_ instruction: EngineInstruction) async {
        switch instruction {
        case let .load(bookId, prepared, generation):
            factGeneration = generation
            load(bookId: bookId, prepared: prepared)
        case .play: await play()
        case .pause: await pause()
        case .seek(let positionMs): await seek(toMs: positionMs)
        case let .setRate(rate, isReset): await setRate(rate, isReset: isReset)
        case let .setBoost(db, isReset): if isReset { resetBoost(defaultDb: db) } else { setBoost(db) }
        case .setVolume(let volume): await engine.setVolume(volume)
        case .stop: await stopEngine()
        }
    }

    /// Load a prepared book and play it from `prepared.resumePositionMs`. Safe while another book
    /// plays or loads: the in-flight load is superseded, the outgoing book's place is saved, and its
    /// metadata is replaced synchronously, so a switch never leaks the old book's audio or chapters.
    private func load(bookId: String, prepared: PreparedPlayback) {
        pauseCause = nil
        supersedeLoad()
        let generation = loadGeneration
        // Gate engine events for the whole switch→load window (see `isEngineLoading`).
        isEngineLoading = true
        playWhenLoaded = true
        pendingSeekMs = nil
        // Save the outgoing book's place — a switch must not lose it. A paused book already did.
        let hadLoadedBook = engineBook != nil
        if let outgoing = engineBook, isPlaybackActive { reportPaused(outgoing.bookId) }

        resetMetadataForSwitch()
        adopt(prepared)
        let segments = AudioSegment.resolve(prepared.timeline.files)
        guard !segments.isEmpty else {
            engineBook = nil
            if hadLoadedBook { Task { await engine.pause() } }
            fail(.couldNotStart(detail: "no audio"))
            return
        }
        // A book streams if any file has no local path — set BEFORE the phase change so the
        // `enginePhase` didSet's bandwidth signal sees the right streaming-ness for this book.
        isCurrentBookStreaming = prepared.timeline.files.contains { $0.localPath == nil }
        // Honest buffering: the book with its real duration the instant we have it, before the
        // (possibly slow, streaming) load. The first real "playing" status promotes it.
        engineBook = EngineBook(bookId: bookId, durationMs: prepared.timeline.totalDurationMs)
        enginePhase = .buffering
        lastSyncedChapterIndex = chapterIndex
        updateNowPlaying()

        loadTask = Task {
            // Silence the outgoing book immediately, before the (possibly slow) load.
            if hadLoadedBook { await engine.pause() }
            // `load` awaits readiness and seeks to the resume position before returning.
            let didLoad = await engine.load(segments: segments, startPositionMs: prepared.resumePositionMs)
            // Not started here: the start queues behind the instructions already received, so a seek or pause
            // sent while the book loaded is applied before it plays, however late the drain reaches them.
            steps.yield(.loadResolved(LoadedBook(
                bookId: bookId, prepared: prepared, generation: generation, didLoad: didLoad)))
        }
    }

    /// The prepared book's metadata, adopted once per load.
    private func adopt(_ prepared: PreparedPlayback) {
        bookTitle = prepared.bookTitle
        authorName = prepared.bookAuthor
        coverPath = prepared.coverPath
        chapters = prepared.chapters
        // The single boundary crossing per book load: bridge each Kotlin `Chapter` once (rule 8).
        chapterRows = prepared.chapters.map {
            ChapterRowModel(id: $0.id, title: $0.title, startMs: $0.startTime, durationMs: $0.duration)
        }
        playbackSpeed = prepared.resumeSpeed
        // Seed the tracker with the resume position (paused) so the lock screen and the smooth thumb
        // show the right place before the engine's first real sample lands.
        positionTracker.update(positionMs: prepared.resumePositionMs, rate: 0)
        refreshChapterIndex()
        lastReportedPositionMs = prepared.resumePositionMs
    }

    /// Start a book whose engine load has resolved, in its turn among the instructions.
    func startLoaded(_ book: LoadedBook) async {
        let (bookId, prepared, generation) = (book.bookId, book.prepared, book.generation)
        guard !isSuperseded(generation) else { return }
        // The load is done — lift the event gate so the engine's real events drive the phase.
        isEngineLoading = false
        guard book.didLoad else {
            fail(.couldNotStart(detail: "engine load failed"))
            return
        }

        await applyGain(from: prepared)
        await engine.setRate(prepared.resumeSpeed)
        guard !isSuperseded(generation) else { return }
        if let pendingSeekMs {
            self.pendingSeekMs = nil
            await seek(toMs: pendingSeekMs)
        }
        guard playWhenLoaded else {
            enginePhase = .paused
            updateNowPlaying()
            return
        }
        await engine.play()
        guard !isSuperseded(generation) else { return }

        // Report started only after the engine has actually been told to play, and only for the
        // book that survived the switch — so a superseded load never reports a phantom start.
        lastReportedPositionMs = bookPositionMs
        progress.onPlaybackStarted(bookId: bookId, positionMs: bookPositionMs,
            speed: prepared.resumeSpeed, durationMs: prepared.timeline.totalDurationMs)
    }

    /// The engine could not load or play the book. The failure is reported typed and the book is no longer
    /// held. Playing it again goes back through the shared player, which prepares it afresh: the preparation
    /// that just failed (its URLs, local paths, token) is never replayed.
    func fail(_ failure: EngineFailure) {
        isEngineLoading = false
        engineBook = nil
        facts.failure(failure, generation: factGeneration)
        enginePhase = .paused
        updateNowPlaying()
    }

    func play() async {
        pauseCause = nil
        if isEngineLoading { playWhenLoaded = true; return }
        // Nothing held (a failure dropped the book): the shared player prepares it afresh, never a `.play` here.
        guard let loaded = engineBook else { return }
        guard !isPlaybackActive else { return }
        await engine.play()
        enginePhase = .playing
        progress.onPlaybackStarted(bookId: loaded.bookId, positionMs: bookPositionMs,
            speed: playbackSpeed, durationMs: bookDurationMs)
        updateNowPlaying()
    }

    func pause() async {
        pauseCause = nil
        if isEngineLoading { playWhenLoaded = false; return }
        guard let loaded = engineBook, isPlaybackActive else { return }
        await engine.pause()
        enginePhase = .paused
        reportPaused(loaded.bookId)
        updateNowPlaying()
    }

    /// Seek to a whole-book position in milliseconds.
    func seek(toMs positionMs: Int64) async {
        if isEngineLoading { pendingSeekMs = positionMs; return }
        // Nothing held: the shared player keeps the place and starts the fresh prepare there.
        guard let loaded = engineBook else { return }
        // Capture the pre-seek position BEFORE issuing the seek so the span split gets a correct edge.
        let beforeMs = bookPositionMs
        // Split the listening span (finalize pre-seek at `beforeMs`, reopen at `positionMs`) so the
        // jumped-over range isn't fabricated as listened content. This also persists the new position.
        progress.onSeek(
            bookId: loaded.bookId, beforeMs: beforeMs, afterMs: positionMs, speed: playbackSpeed,
            durationMs: bookDurationMs)
        lastReportedPositionMs = positionMs
        facts.position(ms: positionMs, generation: factGeneration)
        // Move the clock before stamping now-playing, which reads it: the lock screen shows the new time at once.
        positionTracker.update(positionMs: positionMs, rate: isPlaying ? Double(playbackSpeed) : 0)
        refreshChapterIndex()
        lastSyncedChapterIndex = chapterIndex
        updateNowPlaying()
        // Awaited, so a play the shared player sends after the seek reaches the engine after it (R-4).
        await engine.seek(toMs: positionMs)
    }

    func setRate(_ speed: Float, isReset: Bool) async {
        playbackSpeed = speed
        if let id = engineBook?.bookId {
            if isReset {
                progress.onSpeedReset(bookId: id, positionMs: bookPositionMs, defaultSpeed: speed)
            } else {
                progress.onSpeedChanged(bookId: id, positionMs: bookPositionMs, newSpeed: speed)
            }
        }
        updateNowPlaying()
        await engine.setRate(speed)
    }

    /// Reset the metadata and position state for a fresh load. Keeps `playbackSpeed` and the
    /// volume boost (re-derived from the prepared book) to avoid a flash back to defaults.
    private func resetMetadataForSwitch() {
        bookTitle = ""
        authorName = ""
        coverPath = nil
        chapters = []
        chapterRows = []
        gain.clearMeasurements()
        positionTracker.reset()
        refreshChapterIndex()
        lastReportedPositionMs = 0
        lastSyncedChapterIndex = -1
    }

    /// Recompute the memoized chapter index from `chapterRows` and the tracker's coarse position.
    func refreshChapterIndex() {
        chapterIndex = ChapterMath.index(forPositionMs: displayPositionMs, in: chapterRows) ?? 0
    }

    /// Persist the current position immediately, even if the calling task is cancelled. Called when a
    /// window scene enters the background (`RootView`'s scene-phase handler in `ListenUpApp.swift`, under a
    /// `beginBackgroundTask` assertion) — the periodic 5 s tick is not enough to guarantee the place survives
    /// a kill. Pause and seek persist through the shared tracker (`onPlaybackPaused`, `onSeek`), not here.
    func saveCurrentPosition() async {
        // Key on the book the engine holds: saving during a load would zero an untouched resume point.
        guard let id = engineBook?.bookId else { return }
        await savePositionUncancellably(bookId: id, positionMs: bookPositionMs, durationMs: bookDurationMs)
    }

    /// Unload the engine — sign-out's teardown, awaited so the audio session is deactivated and the
    /// engine unloaded before it returns — and tell the shared player. Not terminal: the coordinator
    /// is an app-lifetime singleton and plays the next book.
    func stop() async {
        await stopEngine()
        facts.unloaded(generation: factGeneration)
    }

    /// Abandon any in-flight load: `Task.cancel()` alone does not interrupt its awaits, so bump the epoch and it
    /// bails at its next `isSuperseded` check instead of resurrecting playback.
    func supersedeLoad() {
        loadTask?.cancel()
        loadGeneration &+= 1
    }

    /// The `.stop` instruction's body. The shared player already knows (it issued the stop), so this
    /// reports nothing back: an `unloaded` fact here could land after the next book's load and drop it.
    private func stopEngine() async {
        pauseCause = nil
        // Record the place before forgetting the book (a Close mid-play must not lose up to 5 s).
        let place = isEngineLoading
            ? nil : engineBook.map { (bookId: $0.bookId, positionMs: bookPositionMs, durationMs: $0.durationMs) }
        if let place, isPlaybackActive { reportPaused(place.bookId) }
        supersedeLoad()
        isEngineLoading = false
        engineBook = nil
        // Return to idle so the didSet releases the download-yield signal.
        enginePhase = .idle
        resetMetadataForSwitch()
        updateNowPlaying()
        if let place {
            await savePositionUncancellably(
                bookId: place.bookId, positionMs: place.positionMs, durationMs: place.durationMs)
        }
        await engine.deactivateSession()
        await engine.unload()
    }

    /// A teardown save: the bridged Kotlin suspend cancels its coroutine when the calling task is cancelled,
    /// and teardown is exactly when tasks get cancelled. Android runs the same save under `NonCancellable`.
    private func savePositionUncancellably(bookId: String, positionMs: Int64, durationMs: Int64) async {
        await uncancellable { @MainActor in
            await self.progress.savePositionNow(bookId: bookId, positionMs: positionMs, durationMs: durationMs)
        }
    }

    /// Whether a newer `.load` or `.stop` has superseded the load that captured `generation`.
    func isSuperseded(_ generation: Int) -> Bool {
        LoadGeneration.isSuperseded(taskGeneration: generation, current: loadGeneration)
    }
}
