import Foundation

/// The ordered sign-out steps, extracted so the ordering invariant is pinnable
/// (`SettingsObserver` wraps a Kotlin `SettingsViewModel` and isn't constructible from Swift).
///
/// **Why iOS stops playback here rather than through the shared flow.** The shared
/// `LogoutUseCase` clears playback via `PlaybackStateProvider.clearPlayback()`, which Android's
/// `PlaybackManager` implements. iOS has no `PlaybackManager` — playback is native, owned by
/// `PlayerCoordinator` — so the shared use case receives `null` there and cannot stop the
/// engine. Routing iOS through that seam would also mean adapting an `async` teardown to a
/// synchronous `clearPlayback()`, i.e. a fire-and-forget `Task { await stop() }`, discarding
/// exactly the determinism `PlayerCoordinator.stop()` is built for (it deactivates the audio
/// session and releases the engine *before* returning).
enum SignOutSequence {
    /// Stops playback, then clears the session — in that order, awaiting the teardown.
    ///
    /// The order is load-bearing: `clearSession` runs the shared logout, which revokes the
    /// session server-side and drops the tokens. A stream still running at that point would be
    /// left issuing requests with a revoked token.
    @MainActor
    static func run(
        stopPlayback: () async -> Void,
        clearSession: () -> Void
    ) async {
        await stopPlayback()
        clearSession()
    }
}
