import Foundation

/// Which Playback menu commands can run, and what the play/pause item says — derived from the
/// shared player's state so the menu bar never offers a command that would do nothing.
///
/// HIG, The menu bar: people look for app-specific commands there, and a command that cannot run
/// right now stays listed but dimmed rather than disappearing.
struct PlaybackCommandState: Equatable {
    /// Play/Pause can run: a book is loaded and not mid-prepare (a failed one retries).
    let canTogglePlayback: Bool
    /// The item reads Pause (audio advancing or buffering) rather than Play.
    let showsPause: Bool
    /// Skips, chapter jumps and the sleep timer need a loaded book.
    let canSeek: Bool
    let hasPreviousChapter: Bool
    let hasNextChapter: Bool
    /// "Add more time" can run: a countdown is ticking on a loaded book. An end-of-chapter timer
    /// has no countdown to grow (`SleepTimerOption.canExtend`).
    let canExtendSleepTimer: Bool

    /// Nothing loaded — or no window is showing the tab shell.
    static let unavailable = PlaybackCommandState(
        canTogglePlayback: false, showsPause: false, canSeek: false,
        hasPreviousChapter: false, hasNextChapter: false, canExtendSleepTimer: false
    )

    static func from(snapshot: PlayerSnapshot) -> PlaybackCommandState {
        guard snapshot.isVisible else { return .unavailable }
        return PlaybackCommandState(
            // Busy while buffering still pauses; only a prepare in flight has nothing to toggle.
            canTogglePlayback: !snapshot.isPreparing,
            showsPause: snapshot.isPlaying || snapshot.isBuffering,
            canSeek: snapshot.showsSkipBack,
            hasPreviousChapter: snapshot.showsSkipBack && snapshot.chapterIndex > 0,
            hasNextChapter: snapshot.canSkipToNextChapter,
            canExtendSleepTimer: SleepTimerOption.canExtend(
                isActive: snapshot.sleepTimerActive, isEndOfChapter: snapshot.sleepTimerIsEndOfChapter
            )
        )
    }
}
