import Testing
@testable import ListenUpActivityKit

/// Records which control method the intent invoked.
@MainActor
final class FakePlaybackController: PlaybackControlling {
    var toggled = false
    var skippedForward = false
    var skippedBackward = false

    func togglePlayPause() { toggled = true }
    func skipForward() { skippedForward = true }
    func skipBackward() { skippedBackward = true }
}

struct PlaybackIntentsTests {

    @Test func togglePlaybackIntentRoutesToTogglePlayPause() async throws {
        let fake = await FakePlaybackController()
        var intent = TogglePlaybackIntent()
        intent.playback = fake

        _ = try await intent.perform()

        #expect(await fake.toggled)
    }

    @Test func skipForwardIntentRoutesToSkipForward() async throws {
        let fake = await FakePlaybackController()
        var intent = SkipForwardIntent()
        intent.playback = fake

        _ = try await intent.perform()

        #expect(await fake.skippedForward)
    }

    @Test func skipBackwardIntentRoutesToSkipBackward() async throws {
        let fake = await FakePlaybackController()
        var intent = SkipBackwardIntent()
        intent.playback = fake

        _ = try await intent.perform()

        #expect(await fake.skippedBackward)
    }
}
