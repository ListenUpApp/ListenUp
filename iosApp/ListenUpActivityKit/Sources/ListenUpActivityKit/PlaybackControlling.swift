/// The playback-control seam between the Live Activity intents (in this package)
/// and the app's player. The package defines the protocol; the app provides the
/// implementation and registers it with `AppDependencyManager`.
@MainActor
public protocol PlaybackControlling: Sendable {
    func togglePlayPause()
    func skipForward()
    func skipBackward()

    /// Start playback of a specific book by id — the "Play <book> in ListenUp"
    /// App Intent / Siri path. Forwards to the app's `PlayerCoordinator`.
    func playBook(id: String)
}
