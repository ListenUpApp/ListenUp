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
/// coordinator is the sole consumer.
actor AudioEngine {
    /// The engine's event stream. Created once at init; consumed once.
    nonisolated let events: AsyncStream<AudioEngineEvent>
    private let continuation: AsyncStream<AudioEngineEvent>.Continuation

    private let player = AVQueuePlayer()
    private var segments: [AudioSegment] = []
    private var rate: Float = 1.0
    private var timeObserver: Any?

    init() {
        var capturedContinuation: AsyncStream<AudioEngineEvent>.Continuation!
        events = AsyncStream { capturedContinuation = $0 }
        continuation = capturedContinuation
    }

    /// Configure the audio session and enqueue `segments` for playback. Does not
    /// start playback — the caller follows with `play()`.
    func load(segments: [AudioSegment], startPositionMs: Int64) {
        self.segments = segments
        configureAudioSession()
        player.removeAllItems()
        for segment in segments {
            player.insert(AVPlayerItem(url: segment.url), after: nil)
        }
        installTimeObserver()
        observeCurrentItem()
        if startPositionMs > 0 {
            seek(toMs: startPositionMs)
        }
        emit(.statusChanged(.ready))
    }

    /// Begin (or resume) playback at the current rate.
    func play() {
        player.rate = rate
    }

    /// Pause playback, leaving the position intact.
    func pause() {
        player.pause()
    }

    /// Seek to a whole-book position in milliseconds.
    func seek(toMs positionMs: Int64) {
        guard let index = SegmentMath.segmentIndex(forPositionMs: positionMs, in: segments) else {
            return
        }
        let segment = segments[index]
        let withinSegmentMs = positionMs - segment.offsetMs
        // AVQueuePlayer plays items front-to-back; advance the queue to `index`.
        while player.items().count > segments.count - index {
            player.advanceToNextItem()
        }
        let target = CMTime(value: max(0, withinSegmentMs), timescale: 1000)
        player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero)
    }

    /// Set the playback speed. Applied immediately if currently playing.
    func setRate(_ newRate: Float) {
        rate = newRate
        if player.rate > 0 {
            player.rate = newRate
        }
    }

    /// Tear down: stop playback, remove observers, finish the event stream.
    func release() {
        if let timeObserver {
            player.removeTimeObserver(timeObserver)
            self.timeObserver = nil
        }
        player.removeAllItems()
        continuation.finish()
    }

    // MARK: - Private

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .spokenAudio)
        try? session.setActive(true)
    }

    private func installTimeObserver() {
        if let timeObserver {
            player.removeTimeObserver(timeObserver)
        }
        // Sample ~every 250 ms. The callback is `@Sendable`; it only touches the
        // `Sendable` continuation and immutable captures, never actor state.
        let interval = CMTime(value: 250, timescale: 1000)
        let segmentsSnapshot = segments
        let continuation = self.continuation
        let player = self.player
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: interval, queue: .main
        ) { _ in
            let currentItemIndex = segmentsSnapshot.count - player.items().count
            let elapsed = player.currentTime()
            let elapsedMs = Int64(CMTimeGetSeconds(elapsed) * 1000)
            let positionMs = EngineClock.wholeBookPositionMs(
                currentSegmentIndex: max(0, currentItemIndex),
                segments: segmentsSnapshot,
                segmentElapsedMs: elapsedMs
            )
            continuation.yield(.position(ms: positionMs, rate: Double(player.rate)))
        }
    }

    private func observeCurrentItem() {
        // The whole queue finishing is signalled by the last item ending.
        let continuation = self.continuation
        NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification,
            object: segments.isEmpty ? nil : nil,
            queue: .main
        ) { [weak player] _ in
            if player?.items().count == 1 {
                continuation.yield(.ended)
            }
        }
    }

    private func emit(_ event: AudioEngineEvent) {
        continuation.yield(event)
    }
}
