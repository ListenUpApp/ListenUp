import Foundation

/// The ordered sign-out steps, extracted so the ordering invariant is pinnable
/// (`SettingsObserver` wraps a Kotlin `SettingsViewModel` and isn't constructible from Swift).
///
/// **Why iOS stops playback here as well as through the shared flow.** The shared
/// `LogoutUseCase` clears playback via `PlaybackStateProvider.clearPlayback()`, which on iOS only
/// queues a Stop for the engine: it is synchronous and cannot await the native teardown. Relying
/// on it alone would discard exactly the determinism `PlayerCoordinator.stop()` is built for (it
/// deactivates the audio session and releases the engine *before* returning).
enum SignOutSequence {
    /// Stops playback, then clears the session — in that order, awaiting the teardown.
    ///
    /// The order is load-bearing: `clearSession` runs the shared logout, which revokes the
    /// session server-side and drops the tokens. A stream still running at that point would be
    /// left issuing requests with a revoked token.
    static func run(
        stopPlayback: () async -> Void,
        clearSession: () -> Void
    ) async {
        await stopPlayback()
        clearSession()
    }
}
