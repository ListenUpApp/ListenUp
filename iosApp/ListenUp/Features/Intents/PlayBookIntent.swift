import AppIntents
import ListenUpActivityKit

/// "Play <book> in ListenUp" — the media-play App Intent / Siri shortcut.
///
/// Conforms to Apple's `.books.playAudiobook` assistant schema so Siri and Apple
/// Intelligence route audiobook-playback requests in the `books` domain straight to
/// us, and to `AudioPlaybackIntent` so the system classifies it as audio-playback
/// control (Now Playing / Siri / lock-screen audio routing) rather than a generic
/// action.
///
/// Opens the app (playback needs the player foreground) and starts the resolved
/// book via the shared `PlaybackControlling` seam — the same dependency the Live
/// Activity intents resolve in the app's process.
@AppIntent(schema: .books.playAudiobook)
struct PlayBookIntent: AudioPlaybackIntent {
    static let openAppWhenRun = true

    @Parameter(title: "Audiobook")
    var target: BookEntity

    @Dependency var playback: any PlaybackControlling

    @MainActor
    func perform() async throws -> some IntentResult {
        playback.playBook(id: target.id)
        return .result()
    }
}
