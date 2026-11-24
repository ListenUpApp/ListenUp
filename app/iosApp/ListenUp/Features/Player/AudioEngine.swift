import AVFoundation
@preconcurrency import MediaPlayer

/// Wraps `AVQueuePlayer` + `AVAudioSession`. The single isolation domain for all
/// AVFoundation mutation. Emits `AudioEngineEvent`s on one `AsyncStream`; the
/// coordinator is the sole consumer. Framework callbacks (periodic time observer,
/// KVO, `NotificationCenter`) capture only `Sendable` values and are funneled through ONE
/// ordered inbox (`signals`) drained serially on the actor — so two callbacks can never reorder
/// relative to each other (rule 7). The previous `Task { await … }`-per-callback let, e.g., a
/// stale `.buffering` land after `.ready` and demote a playing book to a stuck spinner.
///
/// The framework observer setup and signal handlers live in `AudioEngine+Observers.swift` and the
/// volume-boost gain stage lives in `AudioEngine+Gain.swift`; stored state stays here because Swift
/// extensions cannot declare stored properties.
actor AudioEngine: PlaybackEngine {
    /// The engine's event stream. Created once at init; consumed once.
    nonisolated let events: AsyncStream<AudioEngineEvent>
    let continuation: AsyncStream<AudioEngineEvent>.Continuation

    /// The single ordered inbox: every framework callback `yield`s an [EngineSignal] here (thread-
    /// safe, from any callback thread), and one drain loop processes them FIFO on the actor. This
    /// is the ordering guarantee — no two callbacks can be handled out of order (rule 7).
    private nonisolated let signals: AsyncStream<EngineSignal>
    nonisolated let signalContinuation: AsyncStream<EngineSignal>.Continuation
    /// `release()` is terminal (inbox + event streams finished, drain loop ended), so this flag makes a stray
    /// `load()` fail FAST, not hang the 20 s readiness wait. No production caller; close book/sign-out use `unload()`.
    private var isReleased = false

    /// Replaced only by `rebuildAfterMediaServicesReset()`: a media-services reset kills it.
    private(set) var player = AVQueuePlayer()

    /// Ties the queue player to media-remote. While this session is active, the system derives
    /// the play/pause state and timeline CarPlay and the lock screen render from the player
    /// itself — the authoritative signal — instead of inferring them from audio-session
    /// activity, which is exactly the inference that reports a playing book as paused.
    /// `SystemIntegration` publishes through this session's centers; automatic publishing stays
    /// off because our pushes are chapter-scoped, which the automatic ones are not.
    nonisolated let nowPlayingSession: MPNowPlayingSession
    var segments: [AudioSegment] = []
    /// The live queue — each `AVPlayerItem` paired with the index of the
    /// `AudioSegment` it plays. Rebuilt with fresh items on every cross-segment
    /// seek: an `AVPlayerItem` cannot be re-enqueued once removed, so seeking
    /// backward requires fresh items rather than a destructive `advanceToNextItem`.
    var queue: [(item: AVPlayerItem, segmentIndex: Int)] = []
    private var rate: Float = 1.0
    /// A within-segment seek deferred until the current item reaches `.readyToPlay`
    /// — seeking an unprepared item is imprecise.
    var pendingSeekWithinSegmentMs: Int64?

    var timeObserver: Any?
    var endObserver: NSObjectProtocol?
    /// Observes `AVPlayerItem` error-log entries — the only place AVFoundation
    /// surfaces transient HTTP/streaming failures (403/500/timeout on a range
    /// request) that don't flip the item to `.failed`. Logged for diagnosis.
    var errorLogObserver: NSObjectProtocol?
    var currentItemObservation: NSKeyValueObservation?
    var statusObservation: NSKeyValueObservation?
    /// Player-level "is audio actually advancing" signal. Authoritative for the
    /// buffering↔playing distinction — `isPlaybackLikelyToKeepUp` reports "enough
    /// buffered to be playable", which flips to true *before* audio starts and would
    /// make the UI claim "playing" during the startup buffer (the RC-3 lie).
    var timeControlObservation: NSKeyValueObservation?
    /// Resumed when the current item reaches `.readyToPlay` (true) or `.failed`/timeout
    /// (false). Lets `load` seek precisely *before* any rate is set, so playback never
    /// starts from a playable position-0 sample and then jumps (RC-2).
    private var readyContinuation: CheckedContinuation<Bool, Never>?
    /// The timeout task for the *current* readiness wait. Cancelled the instant the wait
    /// resolves (ready / failed / superseded / release) so a stale timer from a prior `load`
    /// can never fire against a later wait — a rapid A→B→C would otherwise stack timers that
    /// resume whichever continuation exists 20 s later, with an effective timeout near zero.
    private var readyTimeoutTask: Task<Void, Never>?
    /// Epoch for the readiness wait: a timeout resume carries the generation it was armed for
    /// and is ignored unless it still matches — belt-and-braces alongside task cancellation.
    private var readyGeneration = 0

    /// The volume-boost gain stage. `gainCell` is engine-lifetime — the user's boost survives
    /// book switches and queue rebuilds; the ring and meter feed are per-book. Lifecycle lives
    /// in `AudioEngine+Gain.swift`.
    let gainCell = GainCell()
    var sampleRing: SampleRing?
    var meterFeed: LoudnessMeterFeed?

    init() {
        nowPlayingSession = MPNowPlayingSession(players: [player])
        // Manual publishing: the automatic publisher derives elapsed/duration from the player
        // ITEM (the whole audio file) and clobbers the title — tested 2026-08-08, it replaced the
        // chapter-scoped snapshot with a bare 17-hour file timeline. Our pushes stay authoritative.
        // (Neither mode flips the SIMULATOR's play/pause state — its mediaremote ignores the
        // player entirely; state renders correctly only on real hardware.)
        nowPlayingSession.automaticallyPublishesNowPlayingInfo = false

        (events, continuation) = AsyncStream.makeStream(of: AudioEngineEvent.self)
        (signals, signalContinuation) = AsyncStream.makeStream(of: EngineSignal.self)

        // Start the single ordered drain loop: it processes each funneled callback to completion
        // before the next, so callbacks are handled strictly FIFO on the actor. Fire-and-forget —
        // it ends when `release()` finishes the inbox. Captures the stream value (not `self`) so
        // there's no retain cycle; `self?` means a released engine just drops signals until the loop
        // ends.
        Task { [weak self] in
            guard let stream = self?.signals else { return }
            for await signal in stream {
                await self?.handle(signal)
            }
        }
    }

    /// Configure the audio session, enqueue `segments`, and seek to `startPositionMs`
    /// *before returning*. Does not start playback — the caller follows with `play()`.
    ///
    /// Returns `true` once the queue's first item is ready and positioned; `false` on a
    /// configuration/queue failure, a readiness timeout or a resume seek that never lands
    /// (`resumeSeekTimeout`). A `.failed` event is emitted on the configuration/queue paths, and
    /// the coordinator reports every `false` as could-not-start.
    ///
    /// The resume seek happens here — after awaiting readiness, before any rate is set —
    /// rather than being deferred to a KVO callback that fires *after* `play()` has already
    /// begun playing position 0. That deferred seek was the "starts at chapter 1, then
    /// jumps" bug (RC-2).
    func load(segments: [AudioSegment], startPositionMs: Int64) async -> Bool {
        guard !isReleased else {
            // Terminal engine (see `isReleased`): fail fast rather than hang the readiness wait.
            Log.error("AudioEngine.load called after release() — engine is terminal")
            return false
        }
        guard !segments.isEmpty else {
            continuation.yield(.failed(.couldNotStart(detail: "no audio")))
            return false
        }
        self.segments = segments
        await startGainStage()
        guard configureAudioSession() else { return false }
        let startIndex = SegmentMath.segmentIndex(forPositionMs: startPositionMs, in: segments) ?? 0
        let withinSegmentMs = max(0, startPositionMs - segments[startIndex].offsetMs)
        // The initial position is applied by the explicit seek below, not the deferred
        // `.readyToPlay` path — clear any stale pending seek so it can't double-fire.
        pendingSeekWithinSegmentMs = nil
        // Order matters: `rebuildQueue` must populate the queue before
        // `observeCurrentItemTransitions` registers its `.initial` KVO, so the
        // first item gets its status observers (and thus the readiness signal).
        guard rebuildQueue(fromSegment: startIndex) else { return false }
        installTimeObserver()
        observeEnd()
        observeErrorLog()
        observeTimeControlStatus()
        observeCurrentItemTransitions()

        // Preroll: a paused `AVQueuePlayer` will NOT advance a fresh item — especially a
        // streaming one — to `.readyToPlay` on its own; it only loads once told to play. So
        // pump it (rate > 0) to drive readiness, but MUTED, so the user never hears the
        // position-0 (chapter 1) audio before the resume seek lands. Without this the wait
        // below never resolves and `load` times out at 20 s (the "stuck preparing → quits"
        // regression). We restore mute and re-pause before returning; the caller starts
        // audible playback from the correct position.
        player.isMuted = true
        player.rate = rate
        let ready = await awaitCurrentItemReady()
        guard ready else {
            player.pause()
            player.isMuted = false
            Log.error("load: item never reached ready (timeout/failed); aborting")
            return false
        }
        // Ready and playable — seek precisely, then hand back a silent, paused, correctly
        // positioned player for the caller's `play()` to resume audibly. Bounded: AVFoundation can
        // leave this seek pending forever, and an unbounded `load` never returns, so the book spins
        // until the app is killed and a Play only queues behind it.
        if withinSegmentMs > 0 {
            let target = CMTime(value: withinSegmentMs, timescale: 1000)
            let landed = await awaitCompletion(within: Self.resumeSeekTimeout) { done in
                player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero, completionHandler: done)
            }
            guard landed != nil else {
                player.currentItem?.cancelPendingSeeks()
                player.pause()
                player.isMuted = false
                Log.error("load: resume seek did not complete; aborting")
                return false
            }
        }
        player.pause()
        player.isMuted = false
        return true
    }

    /// How long the resume seek in `load` may take. The item is already ready to play when it is
    /// issued, so a seek that has not landed in half the readiness wait is not going to: the load
    /// fails and the shared player shows that the book couldn't be started, with a working Retry.
    static let resumeSeekTimeout: Duration = .seconds(10)

    /// Await the current item reaching `.readyToPlay` (`true`) or `.failed`/timeout
    /// (`false`). The status KVO installed by `observeCurrentItemTransitions` drives the
    /// resume via `resumeReadyIfWaiting`; the timeout guards against a stream that never
    /// becomes ready and never fails (the classic ATS/TLS "just never starts" hang).
    private func awaitCurrentItemReady(timeout: Duration = .seconds(20)) async -> Bool {
        guard let item = player.currentItem else { return false }
        switch item.status {
        case .readyToPlay: return true
        case .failed: return false
        default: break
        }
        // Only one waiter at a time; a fresh `load` supersedes any prior wait (which also
        // cancels that wait's timeout task).
        resumeReadyIfWaiting(false)
        readyGeneration &+= 1
        let generation = readyGeneration
        return await withCheckedContinuation { continuation in
            readyContinuation = continuation
            readyTimeoutTask = Task { [weak self] in
                try? await Task.sleep(for: timeout)
                await self?.resumeReadyIfWaiting(false, forGeneration: generation)
            }
        }
    }

    /// Resume a pending readiness waiter, if any, and tear down its timeout task. Idempotent:
    /// a second call (timeout after success, `.failed` after `.readyToPlay`, or release after
    /// either) is a no-op. `generation`, when supplied by the timeout task, gates the resume so
    /// a stale timer whose wait already resolved (and bumped the generation) cannot fire.
    func resumeReadyIfWaiting(_ ready: Bool, forGeneration generation: Int? = nil) {
        if let generation, generation != readyGeneration { return }
        readyTimeoutTask?.cancel()
        readyTimeoutTask = nil
        guard let continuation = readyContinuation else { return }
        readyContinuation = nil
        continuation.resume(returning: ready)
    }

    /// Begin (or resume) playback at the current rate.
    func play() {
        // Re-asserted on every play, not just the first: another audio app may have taken the
        // active session in between, and an inactive session's player state is invisible to
        // CarPlay and the lock screen.
        nowPlayingSession.becomeActiveIfPossible { became in
            if !became { Log.error("Now-playing session refused activation") }
        }
        player.rate = rate
    }

    /// Pause playback, leaving the position intact.
    func pause() {
        player.pause()
    }

    /// Seek to a whole-book position in milliseconds. Non-destructive in both
    /// directions: a seek into a different segment rebuilds the queue from there
    /// with fresh items.
    func seek(toMs positionMs: Int64) {
        guard let targetIndex = SegmentMath.segmentIndex(forPositionMs: positionMs, in: segments) else {
            return
        }
        let withinSegmentMs = max(0, positionMs - segments[targetIndex].offsetMs)
        if currentSegmentIndex == targetIndex {
            player.seek(
                to: CMTime(value: withinSegmentMs, timescale: 1000),
                toleranceBefore: .zero, toleranceAfter: .zero
            )
        } else {
            let wasPlaying = player.rate > 0
            pendingSeekWithinSegmentMs = withinSegmentMs > 0 ? withinSegmentMs : nil
            rebuildQueue(fromSegment: targetIndex)
            if wasPlaying { player.rate = rate }
        }
    }

    /// Set the playback speed. Applied immediately if currently playing.
    func setRate(_ newRate: Float) {
        rate = newRate
        if player.rate > 0 { player.rate = newRate }
    }

    /// Set the output volume (0.0...1.0). Used for the sleep-timer fade.
    func setVolume(_ volume: Float) {
        player.volume = max(0, min(1, volume))
    }

    /// Set the volume-boost gain. Sits downstream of `setVolume`'s sleep-timer fade in the tap,
    /// so the two compose rather than replace each other.
    func setGainDb(_ db: Float) { gainCell.setGain(db: db) }

    /// Deactivate the shared audio session, notifying other apps they may resume.
    /// Best-effort — a deactivation failure can't strand the user — but logged so it
    /// isn't silently swallowed.
    func deactivateSession() {
        do {
            try AVAudioSession.sharedInstance().setActive(
                false, options: .notifyOthersOnDeactivation
            )
        } catch {
            Log.error("Failed to deactivate audio session", error: error)
        }
    }

    /// Re-assert the shared session active — used when resuming after an
    /// interruption. Best-effort: on failure the subsequent `play()` surfaces
    /// the real symptom, and the error is logged for diagnosis.
    func activateSession() {
        do {
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            Log.error("Failed to reactivate audio session", error: error)
        }
    }

    /// Unload the current book — pause, drop every observer, empty the queue. NOT terminal: a later `load` works.
    func unload() async {
        if let timeObserver {
            player.removeTimeObserver(timeObserver)
            self.timeObserver = nil
        }
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
            self.endObserver = nil
        }
        if let errorLogObserver {
            NotificationCenter.default.removeObserver(errorLogObserver)
            self.errorLogObserver = nil
        }
        currentItemObservation = nil
        statusObservation = nil
        timeControlObservation = nil
        await stopGainStage()
        resumeReadyIfWaiting(false) // unblock a load still awaiting readiness — no leaked suspended task
        player.pause()
        player.removeAllItems()
        queue = []
    }

    /// Everything `load` binds to the player goes with `unload`; the sleep-timer volume, set outside `load`, is
    /// carried over, and the now-playing session is moved onto the new player.
    func rebuildAfterMediaServicesReset() async {
        guard !isReleased else { return }
        await unload()
        let dead = player
        player = AVQueuePlayer()
        player.volume = dead.volume
        nowPlayingSession.removePlayer(dead)
        nowPlayingSession.addPlayer(player)
    }

    /// Terminal — [unload] then close the inbox + event stream; the engine can never load again. No production caller.
    func release() async {
        await unload()
        isReleased = true
        signalContinuation.finish()
        continuation.finish()
    }

    // MARK: - Private

    /// The `AudioSegment` index currently playing, resolved by `AVPlayerItem`
    /// identity — robust against queue rebuilds, unlike a count-based derivation.
    var currentSegmentIndex: Int? {
        guard let current = player.currentItem else { return nil }
        return queue.first { $0.item === current }?.segmentIndex
    }

    /// Configure the shared session for spoken-audio playback. Returns `false` (and emits
    /// a `.failed` event) when the session can't be configured — playback would otherwise
    /// start into dead silence.
    private func configureAudioSession() -> Bool {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .spokenAudio)
            try session.setActive(true)
            return true
        } catch {
            Log.error("Failed to configure audio session", error: error)
            continuation.yield(.failed(.couldNotStart(detail: "audio session / queue")))
            return false
        }
    }

    /// Replace the queue with fresh `AVPlayerItem`s for segments `[startIndex...]`.
    /// Returns `false` (and emits `.failed`) if a segment is rejected.
    @discardableResult
    private func rebuildQueue(fromSegment startIndex: Int) -> Bool {
        player.removeAllItems()
        queue = (startIndex..<segments.count).map { index in
            let item = AVPlayerItem(url: segments[index].url)
            // Attached HERE because this is the only place items are created — every
            // cross-segment seek rebuilds them, so a tap attached elsewhere is lost on seek.
            item.audioMix = makeGainMix()
            return (item: item, segmentIndex: index)
        }
        for entry in queue {
            guard player.canInsert(entry.item, after: nil) else {
                // A rejected segment would silently skip audio and corrupt the user's place.
                // Surface it as a playback failure rather than dropping it without a trace.
                Log.error("AVQueuePlayer rejected segment \(entry.segmentIndex); aborting queue build")
                continuation.yield(.failed(.couldNotStart(detail: "audio session / queue")))
                return false
            }
            player.insert(entry.item, after: nil)
        }
        return true
    }
}
