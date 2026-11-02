import AVFoundation
import Foundation
@preconcurrency import Shared

/// A remote command (lock screen, AirPods, CarPlay) waiting its turn in the coordinator's ordered queue.
enum RemoteAction: Sendable {
    case togglePlayPause, play, pause, skipForward, skipBackward
    case setRate(Float)
    /// A scrub on the lock screen: a position within the current chapter's window.
    case seekWindow(Int64)
    /// A chapter jump: a whole-book position.
    case seekBook(Int64)
}

// Framework callbacks (audio-session interruptions, route changes, remote commands) enter the player only through
// `steps`, so they run in order with the shared player's instructions — never as an unordered `Task` per callback.
extension PlayerCoordinator {
    func observeInterruptions() {
        let name = AVAudioSession.interruptionNotification
        bridge.bind(notificationCenter.notifications(named: name)) { [weak self] note in
            guard let self else { return }
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
            let shouldResume: Bool = {
                guard let opts = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt else { return false }
                return AVAudioSession.InterruptionOptions(rawValue: opts).contains(.shouldResume)
            }()
            let action = InterruptionPolicy.action(type: type, shouldResume: shouldResume)
            steps.yield(.interruption(action))
        }
    }

    func observeRouteChanges() {
        let name = AVAudioSession.routeChangeNotification
        bridge.bind(notificationCenter.notifications(named: name)) { [weak self] note in
            guard let self else { return }
            // The status slot names an AirPlay device; every route change may move it.
            let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
            facts.route(outputs.first { $0.portType == .airPlay }?.portName)
            guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  let reason = AVAudioSession.RouteChangeReason(rawValue: raw),
                  RouteChangePolicy.shouldPause(reason: reason) else { return }
            steps.yield(.interruption(.pause))
        }
    }

    func applyInterruption(_ action: InterruptionPolicy.Action) async {
        guard let loaded = engineBook else { return }
        switch action {
        case .pause:
            // .playing OR .buffering: both mean audio intent is "advancing".
            if isEngineLoading {
                // Nothing has started: cancel the queued start instead of pausing an engine that never played.
                playWhenLoaded = false
                pausedByInterruption = true
            } else if isPlaybackActive {
                pausedByInterruption = true
                await engine.pause()
                enginePhase = .paused
                reportPaused(loaded.bookId)
                updateNowPlaying()
            }
        case .resume:
            guard pausedByInterruption else { return }
            pausedByInterruption = false
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
        case .none:
            break
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
