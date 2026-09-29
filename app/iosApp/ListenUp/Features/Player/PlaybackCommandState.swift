import Foundation

/// Which Playback menu commands can run, and what the play/pause item says — derived from the
/// player's phase so the menu bar never offers a command that would do nothing.
///
/// HIG, The menu bar: people look for app-specific commands there, and a command that cannot run
/// right now stays listed but dimmed rather than disappearing.
struct PlaybackCommandState: Equatable {
    /// Play/Pause can run: a book is loaded, or an errored one can be retried.
    let canTogglePlayback: Bool
    /// The item reads Pause (audio advancing or buffering) rather than Play.
    let showsPause: Bool
    /// Skips, chapter jumps and the sleep timer need a loaded book.
    let canSeek: Bool
    let hasPreviousChapter: Bool
    let hasNextChapter: Bool

    /// Nothing loaded — or no window is showing the tab shell.
    static let unavailable = PlaybackCommandState(
        canTogglePlayback: false, showsPause: false, canSeek: false,
        hasPreviousChapter: false, hasNextChapter: false
    )

    static func from(phase: PlayerPhase, chapterIndex: Int, totalChapters: Int) -> PlaybackCommandState {
        let isLoaded = phase.playingState != nil
        let isErrored: Bool = if case .error = phase { true } else { false }
        let isActive: Bool = switch phase {
        case .playing, .buffering: true
        case .idle, .preparing, .paused, .error: false
        }
        return PlaybackCommandState(
            canTogglePlayback: isLoaded || isErrored,
            showsPause: isLoaded && isActive,
            canSeek: isLoaded,
            hasPreviousChapter: isLoaded && chapterIndex > 0,
            hasNextChapter: isLoaded && chapterIndex < totalChapters - 1
        )
    }
}
