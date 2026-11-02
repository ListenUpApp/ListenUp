import Testing
import AVFoundation
@testable import ListenUp

/// An interruption or a pausing route change that lands while a book loads keeps it from starting, and a resume
/// that follows lets it start. Each test syncs on `pausedByInterruption` (observable, set by the coordinator the
/// moment it handles the notification) before releasing the load, so the interleaving is forced, not slept.
@Suite("Interruption during a load", .serialized)
@MainActor
struct InterruptionDuringLoadTests {
    private let center = NotificationCenter()

    private func postInterruption(_ type: AVAudioSession.InterruptionType, shouldResume: Bool = false) {
        var userInfo: [AnyHashable: Any] = [AVAudioSessionInterruptionTypeKey: NSNumber(value: type.rawValue)]
        if shouldResume {
            userInfo[AVAudioSessionInterruptionOptionKey] =
                NSNumber(value: AVAudioSession.InterruptionOptions.shouldResume.rawValue)
        }
        center.post(name: AVAudioSession.interruptionNotification, object: nil, userInfo: userInfo)
    }

    private func postOldDeviceUnavailable() {
        center.post(
            name: AVAudioSession.routeChangeNotification, object: nil,
            userInfo: [AVAudioSessionRouteChangeReasonKey:
                NSNumber(value: AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue)])
    }

    private func startBlockedLoad(_ harness: PlayerHarness) async {
        await harness.engine.setBlockLoad(true)
        harness.instructions.send(.load(bookId: "b1", prepared: .fixture()))
        await harness.engine.waitForLoadEntered()
    }

    private func expectLoadedPaused(_ harness: PlayerHarness) async {
        await awaitObservation { harness.coordinator.enginePhase == .paused }
        #expect(await harness.engine.playCount == 0)
        #expect(harness.progress.startedCalls.isEmpty)
        #expect(harness.progress.pausedCalls.isEmpty)
        #expect(harness.coordinator.engineBook?.bookId == "b1")
    }

    @Test("An interruption that begins while the book loads leaves it loaded and paused")
    func interruptionBeganWhileLoading() async {
        let harness = PlayerHarness(notificationCenter: center)
        await startBlockedLoad(harness)

        postInterruption(.began)
        await awaitObservation { harness.coordinator.pausedByInterruption }
        await harness.engine.releaseLoad()

        await expectLoadedPaused(harness)
    }

    @Test("A pausing route change while the book loads leaves it loaded and paused")
    func routeChangeWhileLoading() async {
        let harness = PlayerHarness(notificationCenter: center)
        await startBlockedLoad(harness)

        postOldDeviceUnavailable()
        await awaitObservation { harness.coordinator.pausedByInterruption }
        await harness.engine.releaseLoad()

        await expectLoadedPaused(harness)
    }

    @Test("An interruption that ends with shouldResume while the book loads lets it start")
    func interruptionEndedWhileLoadingResumes() async {
        let harness = PlayerHarness(notificationCenter: center)
        await startBlockedLoad(harness)

        postInterruption(.began)
        await awaitObservation { harness.coordinator.pausedByInterruption }
        postInterruption(.ended, shouldResume: true)
        await awaitObservation { !harness.coordinator.pausedByInterruption }
        await harness.engine.releaseLoad()

        await harness.progress.waitForStarted(bookId: "b1")
        #expect(await harness.engine.playCount == 1)
    }
}
