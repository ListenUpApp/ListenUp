import Foundation

/// Starting the loaded book at a chosen position (a chapter's start). Split out of
/// `PlayerCoordinator.swift` (the `+Chapters` precedent) to keep that file inside the 800-line cap.
@MainActor
extension PlayerCoordinator {
    /// Seeks the book already loaded to `positionMs` and plays it, with no reload. Returns false,
    /// doing nothing, when `bookId` is not the loaded book — `play` then prepares it there instead.
    func seekLoadedBook(bookId: String, toMs positionMs: Int64) -> Bool {
        guard phase.playingState?.bookId == bookId else { return false }
        seekTo(positionMs: positionMs)
        if !isPlaybackActive { togglePlayback() }
        return true
    }
}
