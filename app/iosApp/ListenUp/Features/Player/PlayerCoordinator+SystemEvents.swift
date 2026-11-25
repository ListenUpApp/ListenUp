import AVFoundation
import Foundation
@preconcurrency import Shared

/// A remote command (lock screen, AirPods, CarPlay) waiting its turn in the coordinator's ordered queue.
nonisolated enum RemoteAction: Sendable {
    case togglePlayPause, play, pause, skipForward, skipBackward
    case setRate(Float)
    /// A scrub on the lock screen: a position within the current chapter's window.
    case seekWindow(Int64)
    /// A chapter jump: a whole-book position.
    case seekBook(Int64)
}

/// What one audio-session notification asks of the player, read on the thread that posted it.
nonisolated enum AudioSessionEvent: Sendable {
    case interruption(InterruptionPolicy.Action)
    /// Every route change may move the AirPlay device the status slot names; `lostOutput` says the device the book
    /// was playing on went away.
    case routeChanged(lostOutput: Bool)
    /// The system restarted its media services: every audio object the app held is dead.
    case mediaServicesReset

    /// Every notification name `init?(_:)` reads.
    static let observedNames = [
        AVAudioSession.interruptionNotification,
        AVAudioSession.routeChangeNotification,
        AVAudioSession.mediaServicesWereResetNotification
    ]

    init?(_ note: Notification) {
        switch note.name {
        case AVAudioSession.interruptionNotification:
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: raw) else { return nil }
            let options = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt)
                .map(AVAudioSession.InterruptionOptions.init(rawValue:)) ?? []
            self = .interruption(InterruptionPolicy.action(type: type, shouldResume: options.contains(.shouldResume)))
        case AVAudioSession.routeChangeNotification:
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt)
                .flatMap(AVAudioSession.RouteChangeReason.init(rawValue:))
            self = .routeChanged(lostOutput: reason.map(RouteChangePolicy.shouldPause(reason:)) ?? false)
        case AVAudioSession.mediaServicesWereResetNotification:
            self = .mediaServicesReset
        default:
            return nil
        }
    }
}

// Framework callbacks (audio-session interruptions, route changes, remote commands) enter the player only through
// `steps`, so they run in order with the shared player's instructions — never as an unordered `Task` per callback.
extension PlayerCoordinator {
    /// Interruptions and route changes arrive through one stream, so the player handles them in the order the session
    /// posted them. A stream per notification name resumes on its own task, and two posts in quick succession (a call
    /// ending, then the headphones coming out) could reach `steps` in the opposite order.
    func observeAudioSession() {
        let center = notificationCenter
        let (events, sink) = AsyncStream<AudioSessionEvent>.makeStream(bufferingPolicy: .unbounded)
        // `queue: nil` runs each block on the posting thread as the post happens, so the yields keep the posts' order.
        let observers = AudioSessionEvent.observedNames.map { name in
            center.addObserver(forName: name, object: nil, queue: nil) { note in
                if let event = AudioSessionEvent(note) { sink.yield(event) }
            }
        }
        bridge.onRelease {
            for observer in observers { center.removeObserver(observer) }
            sink.finish()
        }
        bridge.bind(events) { [weak self] event in self?.receive(event) }
    }

    private func receive(_ event: AudioSessionEvent) {
        switch event {
        case .interruption(let action):
            steps.yield(.interruption(action))
        case .routeChanged(let lostOutput):
            let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
            facts.route(outputs.first { $0.portType == .airPlay }?.portName)
            if lostOutput { steps.yield(.routeLost) }
        case .mediaServicesReset:
            steps.yield(.mediaServicesReset)
        }
    }

    func applyInterruption(_ action: InterruptionPolicy.Action) async {
        switch action {
        case .pause: await pauseForSystem(.interruption)
        case .resume: await resumeAfterInterruption()
        case .declined: forgetInterruption()
        case .none: break
        }
    }

    /// The interruption ended and the system declined to resume: the book stays paused, and the pause stops being
    /// the interruption's, so a later, unrelated interruption's end cannot start it. A lost route's pause is left.
    private func forgetInterruption() {
        if pauseCause == .interruption { pauseCause = nil }
    }

    func applyRouteLoss() async { await pauseForSystem(.routeLost) }

    /// Pause for a cause outside the user's hands, recording the cause. Only a book that is advancing (playing,
    /// buffering, or loading to play) pauses: an interruption landing on a book already paused records nothing,
    /// so its end can never start what the user or a lost route had stopped.
    private func pauseForSystem(_ cause: PauseCause) async {
        guard let loaded = engineBook else { return }
        // .playing OR .buffering: both mean audio intent is "advancing".
        let isAdvancing = isEngineLoading ? playWhenLoaded : isPlaybackActive
        guard isAdvancing else {
            // Headphones out during a call: its end must not resume the book on the speaker.
            if cause == .routeLost, pauseCause == .interruption { pauseCause = .routeLost }
            return
        }
        pauseCause = cause
        if isEngineLoading {
            // Nothing has started: cancel the queued start instead of pausing an engine that never played.
            playWhenLoaded = false
        } else {
            await engine.pause()
            enginePhase = .paused
            reportPaused(loaded.bookId)
            updateNowPlaying()
        }
    }

    /// An interruption ended and the system says playback may resume: only a pause the interruption caused does.
    /// One the user made, or a lost route's, stays put.
    private func resumeAfterInterruption() async {
        guard let loaded = engineBook, pauseCause == .interruption else { return }
        pauseCause = nil
        if isEngineLoading {
            playWhenLoaded = true
        } else if !isPlaying {
            await engine.activateSession()
            await engine.play()
            enginePhase = .playing
            progress.onPlaybackStarted(bookId: loaded.bookId, positionMs: bookPositionMs,
                speed: playbackSpeed, durationMs: bookDurationMs)
            updateNowPlaying()
        }
    }

    // MARK: - RemoteCommandHandler (engine-level: the session sees the resulting facts)
    //
    // Each callback only enqueues; the guards that read state run when the step does, in order with everything else.

    func remoteTogglePlayPause() { steps.yield(.remote(.togglePlayPause)) }
    func remotePlay() { steps.yield(.remote(.play)) }
    func remotePause() { steps.yield(.remote(.pause)) }
    /// The lock screen's, AirPods' and CarPlay's skips move what the app's do: the interval in seconds of listening,
    /// so interval × speed of book (the shared `skipTargetMs`).
    func remoteSkipForward() { steps.yield(.remote(.skipForward)) }
    func remoteSkipBackward() { steps.yield(.remote(.skipBackward)) }
    func remoteSetRate(_ rate: Float) {
        facts.rate(rate, generation: factGeneration)
        steps.yield(.remote(.setRate(rate)))
    }
    /// The lock-screen scrubber spans the CURRENT CHAPTER (see `updateNowPlaying`), so the position
    /// it reports is window-relative; the step translates it back to a book position.
    func remoteSeek(toWindowPositionMs positionMs: Int64) { steps.yield(.remote(.seekWindow(positionMs))) }

    /// Previous chapter by the shared rule, the one every surface uses; nothing in a chapterless book, and the
    /// system is told so. The decision is synchronous (it only reads chapters); the seek is a queued step.
    func remotePreviousTrack() -> Bool {
        guard let target = ChapterNavigation.shared.previousStartMs(positionMs: bookPositionMs, chapters: chapters)
        else { return false }
        steps.yield(.remote(.seekBook(target)))
        return true
    }

    /// Next chapter by the shared rule; nothing at the last chapter, and the system is told so.
    func remoteNextTrack() -> Bool {
        guard let target = ChapterNavigation.shared.nextStartMs(positionMs: bookPositionMs, chapters: chapters) else {
            return false
        }
        steps.yield(.remote(.seekBook(target)))
        return true
    }

    /// Run one remote command when its turn comes.
    func perform(_ command: RemoteAction) async {
        switch command {
        case .togglePlayPause:
            if isPlaybackActive { await pause() } else { await resumeFromRemote() }
        case .play:
            if !isPlaybackActive { await resumeFromRemote() }
        case .pause:
            if isPlaybackActive { await pause() }
        case .skipForward: await seek(toMs: remoteSkipTarget(forward: true))
        case .skipBackward: await seek(toMs: remoteSkipTarget(forward: false))
        case .setRate(let rate): await setRate(rate, isReset: false)
        case .seekWindow(let positionMs):
            let window = ChapterMath.window(forPositionMs: bookPositionMs, in: chapters, bookDurationMs: bookDurationMs)
            await seek(toMs: ChapterMath.bookPosition(forWindowPositionMs: positionMs, in: window))
        case .seekBook(let positionMs): await seek(toMs: positionMs)
        }
    }

    /// A remote play. With no audio held (a failure dropped the book) the shared player is asked to play, and
    /// prepares the book afresh; replaying the failed preparation here could reuse what broke it.
    private func resumeFromRemote() async {
        if engineBook == nil, !isEngineLoading {
            facts.playRequested(generation: factGeneration)
            return
        }
        await play()
    }

    /// Where a remote skip lands: the interval in seconds of listening, so interval × speed of book, as the app's own
    /// skip moves (the shared `skipTargetMs`).
    func remoteSkipTarget(forward: Bool) -> Int64 {
        ExportedKotlinPackages.com.calypsan.listenup.client.playback.skipTargetMs(
            currentPositionMs: bookPositionMs,
            seconds: Int32(forward ? skipForwardSec : skipBackwardSec),
            speed: playbackSpeed,
            totalDurationMs: bookDurationMs,
            forward: forward
        )
    }
}
