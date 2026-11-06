import Testing
import AVFoundation
@testable import ListenUp

/// Remote commands and route changes join the same ordered queue as the shared player's instructions, so they
/// reach the engine in the order they were issued (`app/iosApp/CLAUDE.md` rule 7). Regression pins: they hold
/// for the queue, and would fail if a callback went back to an unordered `Task`.
@Suite("Framework callbacks run in order with instructions")
@MainActor
struct FrameworkCallbackOrderTests {
    @Test("A remote pause, a remote skip and a shared-player play reach the engine in issue order")
    func remoteCommandsAndInstructionsKeepIssueOrder() async {
        let harness = PlayerHarness()
        harness.instructions.send(.load(bookId: "b1", prepared: .fixture()))
        await harness.engine.waitForPlayCount(1)

        harness.coordinator.remotePause()
        harness.coordinator.remoteSkipForward()
        harness.instructions.send(.play)
        await harness.engine.waitForPlayCount(2)

        #expect(await harness.engine.commandLog.suffix(3) == ["pause", "seek", "play"])
    }

    @Test("A route-change pause posted after an instruction seek lands after the seek")
    func routeChangePauseFollowsEarlierSeek() async {
        let center = NotificationCenter()
        let harness = PlayerHarness(notificationCenter: center)
        harness.instructions.send(.load(bookId: "b1", prepared: .fixture()))
        await harness.engine.waitForPlayCount(1)

        harness.instructions.send(.seek(toMs: 10_000))
        center.post(
            name: AVAudioSession.routeChangeNotification, object: nil,
            userInfo: [AVAudioSessionRouteChangeReasonKey:
                NSNumber(value: AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue)])
        await harness.engine.waitUntilPaused()

        #expect(await harness.engine.commandLog.suffix(2) == ["seek", "pause"])
    }
}
