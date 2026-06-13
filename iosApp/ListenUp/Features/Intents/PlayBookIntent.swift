import AppIntents
import ListenUpActivityKit

/// "Play <book> in ListenUp" — the media-play App Intent / Siri shortcut.
///
/// Opens the app (playback needs the player foreground) and starts the resolved
/// book via the shared `PlaybackControlling` seam, the same dependency the Live
/// Activity intents resolve in the app's process.
struct PlayBookIntent: AppIntent {
    static let title: LocalizedStringResource = "Play Audiobook"
    static let openAppWhenRun = true

    @Parameter(title: "Audiobook")
    var book: BookEntity

    @Dependency var playback: any PlaybackControlling

    @MainActor
    func perform() async throws -> some IntentResult {
        playback.playBook(id: book.id)
        return .result()
    }

    static var parameterSummary: some ParameterSummary {
        Summary("Play \(\.$book)")
    }
}
