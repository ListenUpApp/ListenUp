import AVFoundation

/// A raw framework callback (KVO / periodic time observer / `NotificationCenter`), funneled
/// through the engine's single ordered inbox so they are drained in FIFO order on the actor.
/// Carries only `Sendable` values so it can cross from the callback thread to the actor safely.
nonisolated enum EngineSignal: Sendable {
    case timeControl(AVPlayer.TimeControlStatus)
    case periodicTick
    case itemEnded(ObjectIdentifier)
    case currentItemChanged
    case itemStatus(status: AVPlayerItem.Status, errorMessage: String?, failure: EngineFailure)
}

/// AVFoundation KVO, periodic time observer, and notification observers plus the FIFO signal handlers.
///
/// Split out of `AudioEngine.swift` so the actor core stays compact; stored observer tokens live on
/// `AudioEngine` because Swift extensions cannot declare stored properties.
extension AudioEngine {
    /// Drain one funneled signal on the actor. Called only by the ordered drain loop, so all these
    /// handlers run serially in the order the callbacks fired.
    func handle(_ signal: EngineSignal) {
        switch signal {
        case .timeControl(let status): handleTimeControlStatus(status)
        case .periodicTick: emitPosition()
        case .itemEnded(let itemId): handleItemEnded(itemId)
        case .currentItemChanged: handleCurrentItemChanged()
        case .itemStatus(let status, let msg, let failure):
            handleItemStatus(status, errorMessage: msg, failure: failure)
        }
    }

    /// Observe the player-level `timeControlStatus` — the authoritative "is audio advancing?"
    /// signal. Registered per `load` (like the other observers); `timeControlStatus` is a
    /// property of the player, not the item, so it survives queue rebuilds within a load.
    func observeTimeControlStatus() {
        timeControlObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            guard let self else { return }
            let status = player.timeControlStatus
            self.signalContinuation.yield(.timeControl(status))
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

    func installTimeObserver() {
        if let timeObserver {
            player.removeTimeObserver(timeObserver)
        }
        // Sample ~every 250 ms. The callback captures only the `actor` (which is
        // `Sendable`) and hops back onto it before touching the `AVQueuePlayer`.
        let interval = CMTime(value: 250, timescale: 1000)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] _ in
            guard let self else { return }
            self.signalContinuation.yield(.periodicTick)
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

    func observeEnd() {
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
            self.signalContinuation.yield(.itemEnded(endedItemId))
        }
    }

    /// Log AVFoundation error-log entries. These surface transient HTTP/streaming
    /// failures (e.g. ATS blocking a cleartext URL, a 403/500 on a range request, a
    /// TLS-trust failure) that often *don't* flip the item to `.failed` — the player
    /// just never starts. The single most useful signal for diagnosing why a stream
    /// won't play when the downloaded file plays fine.
    func observeErrorLog() {
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
                    "uri=\(UrlRedaction.withoutQuery(event.uri))"
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
    func observeCurrentItemTransitions() {
        currentItemObservation = player.observe(\.currentItem, options: [.initial, .new]) { [weak self] _, _ in
            guard let self else { return }
            self.signalContinuation.yield(.currentItemChanged)
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
            // Typed from the NSError domain/code — the localized text alone rarely names the cause
            // (ATS, TLS trust, HTTP status); the failure's detail carries both for the log.
            let failure = AudioEngine.failure(for: item.error as NSError?)
            self.signalContinuation.yield(.itemStatus(status: status, errorMessage: errorMessage, failure: failure))
        }
    }

    private func handleItemStatus(_ status: AVPlayerItem.Status, errorMessage: String?, failure: EngineFailure) {
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
            Log.error("AVPlayerItem failed: \(errorMessage ?? "unknown error") [\(failure)]")
            resumeReadyIfWaiting(false)
            continuation.yield(.failed(failure))
        case .unknown:
            break
        @unknown default:
            break
        }
    }
}
