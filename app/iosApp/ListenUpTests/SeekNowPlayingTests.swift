import AVFoundation
import MediaPlayer
import Testing
@testable import ListenUp

/// A seek or skip moves the lock screen's, Control Center's and CarPlay's clock at once, not at the next
/// play, pause or chapter change.
@Suite("A seek moves the now-playing clock")
@MainActor
struct SeekNowPlayingTests {
    private func makeHarness() async -> (PlayerHarness, MPNowPlayingSession) {
        let session = MPNowPlayingSession(players: [AVPlayer()])
        let harness = PlayerHarness(system: SystemIntegration(session: session))
        await harness.loadAndStart("book1", .fixture(durationMs: 600_000))
        return (harness, session)
    }

    private func elapsedSeconds(_ session: MPNowPlayingSession) -> Double? {
        session.nowPlayingInfoCenter.nowPlayingInfo?[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double
    }

    @Test("A seek shows the new elapsed time")
    func seekShowsNewElapsed() async {
        let (harness, session) = await makeHarness()

        await harness.run(.seek(toMs: 120_000))

        #expect(elapsedSeconds(session) == 120.0)
    }

    @Test("A remote skip forward shows the new elapsed time")
    func remoteSkipShowsNewElapsed() async {
        let (harness, session) = await makeHarness()
        let expectedMs = harness.coordinator.remoteSkipTarget(forward: true)

        harness.coordinator.remoteSkipForward()
        await harness.engine.waitUntilSeeked()

        #expect(elapsedSeconds(session) == Double(expectedMs) / 1000)
    }
}
