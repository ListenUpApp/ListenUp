import ListenUpActivityKit

/// The app's `PlaybackControlling` implementation — forwards playback App Intent
/// runs (Siri, Shortcuts, Control Center) to the shared player, through the app-wide `PlayerObserver`.
/// Registered with `AppDependencyManager` at launch (see `ListenUpApp`).
@MainActor
final class PlaybackController: PlaybackControlling {

    private var player: PlayerObserver { Dependencies.shared.playerObserver }

    func togglePlayPause() { player.togglePlayback() }
    func skipForward() { player.skipForward() }
    func skipBackward() { player.skipBackward() }

    /// Resolves the "Play <book> in ListenUp" App Intent to playback start.
    func playBook(id: String) { player.play(bookId: id) }

    /// A loaded book — restored, playing, paused, or one that can't play yet — is what a sleep timer
    /// counts down against; with nothing loaded there is nothing to time.
    var hasLoadedBook: Bool { player.isVisible }

    func setSleepTimer(minutes: Int) { player.setSleepTimer(minutes: minutes) }
}
