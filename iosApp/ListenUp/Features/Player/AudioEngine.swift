import AVFoundation

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

/// Wraps `AVQueuePlayer` + `AVAudioSession`. The single isolation domain for all
/// AVFoundation mutation. Emits `AudioEngineEvent`s on one `AsyncStream`; the
/// coordinator is the sole consumer. Framework callbacks (periodic time observer,
/// KVO, `NotificationCenter`) capture only `Sendable` values and hop back onto
/// the actor before touching the player.
actor AudioEngine: PlaybackEngine {
    /// The engine's event stream. Created once at init; consumed once.
    nonisolated let events: AsyncStream<AudioEngineEvent>
    private let continuation: AsyncStream<AudioEngineEvent>.Continuation

    private let player = AVQueuePlayer()
    private var segments: [AudioSegment] = []
    /// The live queue — each `AVPlayerItem` paired with the index of the
    /// `AudioSegment` it plays. Rebuilt with fresh items on every cross-segment
    /// seek: an `AVPlayerItem` cannot be re-enqueued once removed, so seeking
    /// backward requires fresh items rather than a destructive `advanceToNextItem`.
    private var queue: [(item: AVPlayerItem, segmentIndex: Int)] = []
    private var rate: Float = 1.0
    /// A within-segment seek deferred until the current item reaches `.readyToPlay`
    /// — seeking an unprepared item is imprecise.
    private var pendingSeekWithinSegmentMs: Int64?

    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    /// Observes `AVPlayerItem` error-log entries — the only place AVFoundation
    /// surfaces transient HTTP/streaming failures (403/500/timeout on a range
    /// request) that don't flip the item to `.failed`. Logged for diagnosis.
    private var errorLogObserver: NSObjectProtocol?
    private var currentItemObservation: NSKeyValueObservation?
    private var statusObservation: NSKeyValueObservation?
    /// Player-level "is audio actually advancing" signal. Authoritative for the
    /// buffering↔playing distinction — `isPlaybackLikelyToKeepUp` reports "enough
    /// buffered to be playable", which flips to true *before* audio starts and would
    /// make the UI claim "playing" during the startup buffer (the RC-3 lie).
    private var timeControlObservation: NSKeyValueObservation?
    /// Resumed when the current item reaches `.readyToPlay` (true) or `.failed`/timeout
    /// (false). Lets `load` seek precisely *before* any rate is set, so playback never
    /// starts from a playable position-0 sample and then jumps (RC-2).
    private var readyContinuation: CheckedContinuation<Bool, Never>?

    init() {
        var capturedContinuation: AsyncStream<AudioEngineEvent>.Continuation!
        events = AsyncStream { capturedContinuation = $0 }
        continuation = capturedContinuation
    }

    /// Configure the audio session, enqueue `segments`, and seek to `startPositionMs`
    /// *before returning*. Does not start playback — the caller follows with `play()`.
    ///
    /// Returns `true` once the queue's first item is ready and positioned; `false` on a
    /// configuration/queue failure or a readiness timeout (a `.failed` event is emitted
    /// on the failure paths so the coordinator can surface an error state).
    ///
    /// The resume seek happens here — after awaiting readiness, before any rate is set —
    /// rather than being deferred to a KVO callback that fires *after* `play()` has already
    /// begun playing position 0. That deferred seek was the "starts at chapter 1, then
    /// jumps" bug (RC-2).
    func load(segments: [AudioSegment], startPositionMs: Int64) async -> Bool {
        guard !segments.isEmpty else {
            continuation.yield(.failed(message: "This book has no audio."))
            return false
        }
        self.segments = segments
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

        // Wait for the first item to be playable, then seek precisely — so there is
        // never a playable position-0 state for `play()` to start from.
        guard await awaitCurrentItemReady() else { return false }
        if withinSegmentMs > 0 {
            _ = await player.seek(
                to: CMTime(value: withinSegmentMs, timescale: 1000),
                toleranceBefore: .zero, toleranceAfter: .zero
            )
        }
        return true
    }

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
        // Only one waiter at a time; a fresh `load` supersedes any prior wait.
        resumeReadyIfWaiting(false)
        return await withCheckedContinuation { continuation in
            readyContinuation = continuation
            Task { [weak self] in
                try? await Task.sleep(for: timeout)
                await self?.resumeReadyIfWaiting(false)
            }
        }
    }

    /// Resume a pending readiness waiter, if any. Idempotent: a second call (timeout after
    /// success, or `.failed` after `.readyToPlay`) is a no-op.
    private func resumeReadyIfWaiting(_ ready: Bool) {
        guard let continuation = readyContinuation else { return }
        readyContinuation = nil
        continuation.resume(returning: ready)
    }

    /// Begin (or resume) playback at the current rate.
    func play() {
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

    /// Tear down: stop playback, remove every observer, finish the event stream.
    func release() {
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
        // Unblock any load still awaiting readiness so it doesn't leak a suspended task.
        resumeReadyIfWaiting(false)
        player.removeAllItems()
        queue = []
        continuation.finish()
    }

    // MARK: - Private

    /// The `AudioSegment` index currently playing, resolved by `AVPlayerItem`
    /// identity — robust against queue rebuilds, unlike a count-based derivation.
    private var currentSegmentIndex: Int? {
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
            continuation.yield(.failed(message: "Couldn't start audio playback."))
            return false
        }
    }

    /// Replace the queue with fresh `AVPlayerItem`s for segments `[startIndex...]`.
    /// Returns `false` (and emits `.failed`) if a segment is rejected.
    @discardableResult
    private func rebuildQueue(fromSegment startIndex: Int) -> Bool {
        player.removeAllItems()
        queue = (startIndex..<segments.count).map { index in
            (item: AVPlayerItem(url: segments[index].url), segmentIndex: index)
        }
        for entry in queue {
            guard player.canInsert(entry.item, after: nil) else {
                // A rejected segment would silently skip audio and corrupt the user's place.
                // Surface it as a playback failure rather than dropping it without a trace.
                Log.error("AVQueuePlayer rejected segment \(entry.segmentIndex); aborting queue build")
                continuation.yield(.failed(message: "Couldn't start audio playback."))
                return false
            }
            player.insert(entry.item, after: nil)
        }
        return true
    }

    /// Observe the player-level `timeControlStatus` — the authoritative "is audio advancing?"
    /// signal. Registered per `load` (like the other observers); `timeControlStatus` is a
    /// property of the player, not the item, so it survives queue rebuilds within a load.
    private func observeTimeControlStatus() {
        timeControlObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            guard let self else { return }
            let status = player.timeControlStatus
            // TODO(rule 7): fold this and the other KVO/notification callbacks into a single
            // ordered AsyncStream the actor drains, so currentItem/status/timeControl events
            // can't reorder relative to each other during a queue rebuild.
            Task { await self.handleTimeControlStatus(status) }
        }
    }

    /// Map `timeControlStatus` onto the engine's `buffering`/`ready` status.
    /// `.waitingToPlayAtSpecifiedRate` means the player wants to play but is stalled buffering;
    /// `.playing` means samples are actually flowing. `.paused` emits nothing — a paused
    /// player's status is owned by the coordinator's phase, not the engine.
    private func handleTimeControlStatus(_ status: AVPlayer.TimeControlStatus) {
        switch status {
        case .waitingToPlayAtSpecifiedRate:
            continuation.yield(.statusChanged(.buffering))
        case .playing:
            continuation.yield(.statusChanged(.ready))
        case .paused:
            break
        @unknown default:
            break
        }
    }

    private func installTimeObserver() {
        if let timeObserver {
            player.removeTimeObserver(timeObserver)
        }
        // Sample ~every 250 ms. The callback captures only the `actor` (which is
        // `Sendable`) and hops back onto it before touching the `AVQueuePlayer`.
        let interval = CMTime(value: 250, timescale: 1000)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] _ in
            guard let self else { return }
            Task { await self.emitPosition() }
        }
    }

    private func emitPosition() {
        let elapsedMs = Int64(CMTimeGetSeconds(player.currentTime()) * 1000)
        let positionMs = EngineClock.wholeBookPositionMs(
            currentSegmentIndex: currentSegmentIndex ?? 0,
            segments: segments,
            segmentElapsedMs: elapsedMs
        )
        continuation.yield(.position(ms: positionMs, rate: Double(player.rate)))
    }

    private func observeEnd() {
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let self else { return }
            guard let endedItem = notification.object as? AVPlayerItem else { return }
            // Pass the identity, not the non-`Sendable` `AVPlayerItem`, across the hop.
            let endedItemId = ObjectIdentifier(endedItem)
            Task { await self.handleItemEnded(endedItemId) }
        }
    }

    /// Log AVFoundation error-log entries. These surface transient HTTP/streaming
    /// failures (e.g. ATS blocking a cleartext URL, a 403/500 on a range request, a
    /// TLS-trust failure) that often *don't* flip the item to `.failed` — the player
    /// just never starts. The single most useful signal for diagnosing why a stream
    /// won't play when the downloaded file plays fine.
    private func observeErrorLog() {
        if let errorLogObserver {
            NotificationCenter.default.removeObserver(errorLogObserver)
        }
        errorLogObserver = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.newErrorLogEntryNotification,
            object: nil,
            queue: .main
        ) { notification in
            guard let item = notification.object as? AVPlayerItem,
                  let event = item.errorLog()?.events.last else { return }
            Log.error(
                "AVPlayer streaming error: status=\(event.errorStatusCode) " +
                    "domain=\(event.errorDomain) comment=\(event.errorComment ?? "—") " +
                    "uri=\(event.uri ?? "—")"
            )
        }
    }

    private func handleItemEnded(_ itemId: ObjectIdentifier) {
        guard let last = queue.last else { return }
        // The whole book has ended only when the *final* segment's item finishes.
        if ObjectIdentifier(last.item) == itemId, last.segmentIndex == segments.count - 1 {
            continuation.yield(.ended)
        }
    }

    /// Re-attach per-item observers whenever the current item changes — start of
    /// playback, a natural segment advance, or a queue rebuild from a seek.
    private func observeCurrentItemTransitions() {
        currentItemObservation = player.observe(\.currentItem, options: [.initial, .new]) { [weak self] _, _ in
            guard let self else { return }
            Task { await self.handleCurrentItemChanged() }
        }
    }

    private func handleCurrentItemChanged() {
        attachItemObservers(to: player.currentItem)
    }

    private func attachItemObservers(to item: AVPlayerItem?) {
        statusObservation = nil
        guard let item else { return }
        statusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            guard let self else { return }
            let status = item.status
            let errorMessage = item.error?.localizedDescription
            // Capture the NSError domain/code too — the localized text alone rarely
            // names the cause (ATS, TLS trust, HTTP status).
            let detail = (item.error as NSError?).map { " [\($0.domain) \($0.code)]" } ?? ""
            Task { await self.handleItemStatus(status, errorMessage: errorMessage, errorDetail: detail) }
        }
    }

    private func handleItemStatus(_ status: AVPlayerItem.Status, errorMessage: String?, errorDetail: String) {
        switch status {
        case .readyToPlay:
            // A deferred cross-segment seek (set by `seek(toMs:)`, which rebuilds the queue and
            // can't seek an unprepared item). The initial resume seek is done inline in `load`,
            // which clears `pendingSeekWithinSegmentMs`, so this only fires for later seeks.
            if let pending = pendingSeekWithinSegmentMs {
                pendingSeekWithinSegmentMs = nil
                player.seek(
                    to: CMTime(value: pending, timescale: 1000),
                    toleranceBefore: .zero, toleranceAfter: .zero
                )
            }
            // Readiness unblocks `load`'s seek-before-play. The buffering↔playing signal is NOT
            // emitted here — `isReadyToPlay` means "playable", not "playing"; `timeControlStatus`
            // owns that distinction so the UI can't claim "playing" during the startup buffer.
            resumeReadyIfWaiting(true)
        case .failed:
            Log.error("AVPlayerItem failed: \(errorMessage ?? "unknown error")\(errorDetail)")
            resumeReadyIfWaiting(false)
            continuation.yield(.failed(message: errorMessage ?? "Playback failed."))
        case .unknown:
            break
        @unknown default:
            break
        }
    }
}
