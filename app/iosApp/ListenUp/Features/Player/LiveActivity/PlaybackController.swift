import ListenUpActivityKit

/// The app's `PlaybackControlling` implementation — forwards Live Activity intent
/// taps to the app-wide `PlayerCoordinator` via its remote-command handlers.
/// Registered with `AppDependencyManager` at launch (see `ListenUpApp`).
@MainActor
final class PlaybackController: PlaybackControlling {

    private var coordinator: PlayerCoordinator { Dependencies.shared.playerCoordinator }

    func togglePlayPause() { coordinator.remoteTogglePlayPause() }
    func skipForward() { coordinator.remoteSkipForward() }
    func skipBackward() { coordinator.remoteSkipBackward() }

    /// Resolves the "Play <book> in ListenUp" App Intent to playback start.
    func playBook(id: String) { coordinator.play(bookId: id) }
}
