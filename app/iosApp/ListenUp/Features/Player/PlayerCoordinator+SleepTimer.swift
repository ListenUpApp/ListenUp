import Foundation

/// The sleep-timer commands — thin pass-throughs to the shared `SleepTimerManager` behind the
/// `SleepTiming` seam, used by the player's sleep menu and the iPad keyboard menu.
/// Split out of `PlayerCoordinator.swift` (the `+Chapters` / `+NowPlaying` precedent) to keep that
/// file inside the 800-line cap; the timer's observed state stays there with the other stored state.
@MainActor
extension PlayerCoordinator {
    func setSleepTimer(minutes: Int) { sleep.setDurationTimer(minutes: minutes) }
    func setSleepTimerEndOfChapter() { sleep.setEndOfChapterTimer() }
    func cancelSleepTimer() { sleep.cancelTimer() }
    func extendSleepTimer(minutes: Int) { sleep.extendTimer(minutes: minutes) }
}
