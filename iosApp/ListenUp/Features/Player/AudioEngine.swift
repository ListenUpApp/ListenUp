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
actor AudioEngine {
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
    private var currentItemObservation: NSKeyValueObservation?
    private var statusObservation: NSKeyValueObservation?
    private var keepUpObservation: NSKeyValueObservation?

    init() {
        var capturedContinuation: AsyncStream<AudioEngineEvent>.Continuation!
        events = AsyncStream { capturedContinuation = $0 }
        continuation = capturedContinuation
    }

    /// Configure the audio session and enqueue `segments`, positioned at
    /// `startPositionMs`. Does not start playback — the caller follows with `play()`.
    func load(segments: [AudioSegment], startPositionMs: Int64) {
        guard !segments.isEmpty else {
            continuation.yield(.failed(message: "This book has no audio."))
            return
        }
        self.segments = segments
        configureAudioSession()
        let startIndex = SegmentMath.segmentIndex(forPositionMs: startPositionMs, in: segments) ?? 0
        let withinSegmentMs = startPositionMs - segments[startIndex].offsetMs
        pendingSeekWithinSegmentMs = withinSegmentMs > 0 ? withinSegmentMs : nil
        // Order matters: `rebuildQueue` must populate the queue before
        // `observeCurrentItemTransitions` registers its `.initial` KVO, so the
        // first item gets its status observers (and thus the deferred resume seek).
        rebuildQueue(fromSegment: startIndex)
        installTimeObserver()
        observeEnd()
        observeCurrentItemTransitions()
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
        currentItemObservation = nil
        statusObservation = nil
        keepUpObservation = nil
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

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .spokenAudio)
        try? session.setActive(true)
    }

    /// Replace the queue with fresh `AVPlayerItem`s for segments `[startIndex...]`.
    private func rebuildQueue(fromSegment startIndex: Int) {
        player.removeAllItems()
        queue = (startIndex..<segments.count).map { index in
            (item: AVPlayerItem(url: segments[index].url), segmentIndex: index)
        }
        for entry in queue where player.canInsert(entry.item, after: nil) {
            player.insert(entry.item, after: nil)
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
        keepUpObservation = nil
        guard let item else { return }
        statusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            guard let self else { return }
            let status = item.status
            let errorMessage = item.error?.localizedDescription
            Task { await self.handleItemStatus(status, errorMessage: errorMessage) }
        }
        keepUpObservation = item.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { [weak self] item, _ in
            guard let self else { return }
            let likelyToKeepUp = item.isPlaybackLikelyToKeepUp
            Task { await self.handleKeepUp(likelyToKeepUp) }
        }
    }

    private func handleKeepUp(_ likelyToKeepUp: Bool) {
        continuation.yield(.statusChanged(likelyToKeepUp ? .ready : .buffering))
    }

    private func handleItemStatus(_ status: AVPlayerItem.Status, errorMessage: String?) {
        switch status {
        case .readyToPlay:
            if let pending = pendingSeekWithinSegmentMs {
                pendingSeekWithinSegmentMs = nil
                player.seek(
                    to: CMTime(value: pending, timescale: 1000),
                    toleranceBefore: .zero, toleranceAfter: .zero
                )
            }
            continuation.yield(.statusChanged(.ready))
        case .failed:
            continuation.yield(.failed(message: errorMessage ?? "Playback failed."))
        case .unknown:
            break
        @unknown default:
            break
        }
    }
}
