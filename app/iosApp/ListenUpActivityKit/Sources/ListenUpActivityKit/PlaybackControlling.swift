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

/// Resolves "the book to resume" — the most-recently-played in-progress audiobook —
/// for `ResumePlaybackIntent`. Like `PlaybackControlling`, the package defines the
/// protocol (Shared-free, so the package and the widget extension can reference the
/// intent) and the app provides the offline-first implementation, registering it with
/// `AppDependencyManager`.
@MainActor
public protocol LastPlayedBookProviding: Sendable {
    /// The id of the most-recently-played in-progress book, or `nil` when the user
    /// has nothing in progress. Read offline-first from the local store.
    func mostRecentBookId() async -> String?
}
