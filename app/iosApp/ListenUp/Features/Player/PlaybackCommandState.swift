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
    /// Previous chapter can run whenever skips can: in chapter 1, or a chapterless book, it restarts (§3.3).
    let hasPreviousChapter: Bool
    let hasNextChapter: Bool
    /// Show Chapters (⇧⌘C): the book has chapters to show.
    let canShowChapters: Bool
    /// Slower (⌘[) and Faster (⌘]): a loaded book not already at the slowest or fastest speed.
    let canStepSpeedDown: Bool
    let canStepSpeedUp: Bool
    /// Show Now Playing (⌘0): a loaded book whose player is not already showing.
    let canShowNowPlaying: Bool
    /// Close Player (Esc): the player is showing and none of its sheets is open — Esc closes a sheet first.
    let canClosePlayer: Bool
    /// "Add more time" can run: a countdown is ticking on a loaded book. An end-of-chapter timer
    /// has no countdown to grow (`SleepTimerOption.canExtend`).
    let canExtendSleepTimer: Bool

    /// Nothing loaded — or no window is showing the tab shell.
    static let unavailable = PlaybackCommandState(
        canTogglePlayback: false, showsPause: false, canSeek: false,
        hasPreviousChapter: false, hasNextChapter: false, canShowChapters: false,
        canStepSpeedDown: false, canStepSpeedUp: false, canShowNowPlaying: false, canClosePlayer: false,
        canExtendSleepTimer: false
    )

    /// The menu for `snapshot`, with whether the full player is showing and whether one of its sheets is open.
    static func from(
        snapshot: PlayerSnapshot, isPlayerPresented: Bool = false, playerSheetIsOpen: Bool = false
    ) -> PlaybackCommandState {
        guard snapshot.isVisible else { return .unavailable }
        let speed = snapshot.playbackSpeed
        return PlaybackCommandState(
            // Busy while buffering still pauses; only a prepare in flight has nothing to toggle.
            canTogglePlayback: !snapshot.isPreparing,
            showsPause: snapshot.isPlaying || snapshot.isBuffering,
            canSeek: snapshot.showsSkipBack,
            hasPreviousChapter: snapshot.showsSkipBack,
            hasNextChapter: snapshot.canSkipToNextChapter,
            canShowChapters: !snapshot.chapterRows.isEmpty,
            canStepSpeedDown: speed > (PlaybackRates.catalogue.first ?? speed),
            canStepSpeedUp: speed < (PlaybackRates.catalogue.last ?? speed),
            canShowNowPlaying: !isPlayerPresented,
            canClosePlayer: isPlayerPresented && !playerSheetIsOpen,
            canExtendSleepTimer: SleepTimerOption.canExtend(
                isActive: snapshot.sleepTimerActive, isEndOfChapter: snapshot.sleepTimerIsEndOfChapter
            )
        )
    }
}
