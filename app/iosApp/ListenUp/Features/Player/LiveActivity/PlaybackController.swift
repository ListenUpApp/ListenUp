import ListenUpActivityKit

/// The app's `PlaybackControlling` implementation — forwards playback App Intent
/// runs (Siri, Shortcuts, Control Center) to the app-wide `PlayerCoordinator` via its remote-command handlers.
/// Registered with `AppDependencyManager` at launch (see `ListenUpApp`).
@MainActor
final class PlaybackController: PlaybackControlling {

    private var coordinator: PlayerCoordinator { Dependencies.shared.playerCoordinator }

    func togglePlayPause() { coordinator.remoteTogglePlayPause() }
    func skipForward() { coordinator.remoteSkipForward() }
    func skipBackward() { coordinator.remoteSkipBackward() }

    /// Resolves the "Play <book> in ListenUp" App Intent to playback start.
    func playBook(id: String) { coordinator.play(bookId: id) }

    /// A book counts as loaded from the moment `play(bookId:)` starts preparing it; an idle player
    /// and a failed load have nothing for a sleep timer to count down against.
    var hasLoadedBook: Bool {
        switch coordinator.phase {
        case .preparing, .playing, .paused, .buffering: true
        case .idle, .error: false
        }
    }

    func setSleepTimer(minutes: Int) { coordinator.setSleepTimer(minutes: minutes) }
}
