import Testing
@testable import ListenUp

/// Pins the sign-out ordering invariant.
///
/// `SettingsObserver` itself isn't constructible from Swift (`SettingsViewModel` is a
/// Kotlin type with no Swift-constructible init — see `SettingsObserverTests`), so the
/// orderable part is extracted into `SignOutSequence` and pinned here; the observer's
/// wiring to it is proven by the green build.
@Suite("SignOutSequence")
struct SignOutSequenceTests {
    /// Records the order in which the two sign-out steps ran.
    @MainActor
    final class Recorder {
        var steps: [String] = []
    }

    @MainActor
    @Test func stopsPlaybackBeforeClearingTheSession() async {
        let recorder = Recorder()

        await SignOutSequence.run(
            stopPlayback: { recorder.steps.append("stopPlayback") },
            clearSession: { recorder.steps.append("clearSession") }
        )

        // Order is the invariant, not merely that both ran: the shared logout revokes the
        // session server-side and drops the tokens, so a still-live stream would be left
        // making requests with a revoked token.
        #expect(recorder.steps == ["stopPlayback", "clearSession"])
    }

    @MainActor
    @Test func awaitsPlaybackTeardownBeforeClearingTheSession() async {
        let recorder = Recorder()

        await SignOutSequence.run(
            stopPlayback: {
                // PlayerCoordinator.stop() is async by design — it deactivates the audio
                // session and releases the engine before returning. A fire-and-forget
                // teardown would let clearSession win the race.
                await Task.yield()
                recorder.steps.append("stopPlayback")
            },
            clearSession: { recorder.steps.append("clearSession") }
        )

        #expect(recorder.steps == ["stopPlayback", "clearSession"])
    }
}
