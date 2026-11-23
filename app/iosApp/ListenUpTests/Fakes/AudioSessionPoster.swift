import AVFoundation
@testable import ListenUp

/// Posts audio-session notifications on a private center, the one a test injects into its `PlayerHarness`, so a post
/// reaches only that test's coordinator.
///
/// `settle(_:)` is how a test knows the coordinator has handled every post so far. A post only enqueues: the
/// coordinator's observer feeds one event stream, a task forwards each event into `steps`, and the drain handles the
/// steps in turn. A test that checks state straight after a post, or after a fixed wait, reads it before the
/// coordinator got there on a starved runner. A check that something did NOT happen then passes without testing
/// anything, and a check that it did fails.
@MainActor
final class AudioSessionPoster {
    let center = NotificationCenter()
    /// Route changes posted so far. The coordinator reports the route once for each, as it reads the event.
    private var routeChangesPosted = 0

    /// Nonisolated, so a suite can create one in a stored property's default value.
    nonisolated init() {}

    func interruptionBegan() {
        post(AVAudioSession.interruptionNotification, [
            AVAudioSessionInterruptionTypeKey: NSNumber(value: AVAudioSession.InterruptionType.began.rawValue)
        ])
    }

    func interruptionEnded(shouldResume: Bool) {
        var userInfo: [AnyHashable: Any] = [
            AVAudioSessionInterruptionTypeKey: NSNumber(value: AVAudioSession.InterruptionType.ended.rawValue)
        ]
        if shouldResume {
            userInfo[AVAudioSessionInterruptionOptionKey] =
                NSNumber(value: AVAudioSession.InterruptionOptions.shouldResume.rawValue)
        }
        post(AVAudioSession.interruptionNotification, userInfo)
    }

    /// The device the book was playing on went away: headphones out, a Bluetooth speaker off.
    func routeLost() { routeChanged(.oldDeviceUnavailable) }

    func routeChanged(_ reason: AVAudioSession.RouteChangeReason) {
        routeChangesPosted += 1
        post(AVAudioSession.routeChangeNotification, [
            AVAudioSessionRouteChangeReasonKey: NSNumber(value: reason.rawValue)
        ])
    }

    /// Returns once `harness`'s coordinator has handled every notification posted so far.
    ///
    /// Two fences, one for each queue a post passes through. A route change that pauses nothing goes last into the
    /// event stream; the coordinator reports the route as it reads it, so once every posted route change has been
    /// reported, every earlier event is in `steps`. A volume instruction then goes last into `steps`; once the engine
    /// has it, the drain has handled every step before it.
    func settle(_ harness: PlayerHarness) async {
        routeChanged(.categoryChange)
        await harness.facts.waitForRouteCount(routeChangesPosted)
        let volumes = await harness.engine.volumeCount
        harness.instructions.send(.setVolume(1))
        await harness.engine.waitForVolumeCount(volumes + 1)
    }

    private func post(_ name: Notification.Name, _ userInfo: [AnyHashable: Any]) {
        center.post(name: name, object: nil, userInfo: userInfo)
    }
}
