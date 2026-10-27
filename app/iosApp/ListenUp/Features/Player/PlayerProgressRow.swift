import Foundation
@preconcurrency import Shared

/// Native mirror of the shared `PlayerProgress` (~4 Hz): the four positions and what derives from them.
struct PlayerProgressRow: Equatable {
    var chapterPositionMs: Int64 = 0
    var chapterDurationMs: Int64 = 0
    var bookPositionMs: Int64 = 0
    var bookDurationMs: Int64 = 0

    init() {}

    init(_ progress: PlayerProgress) {
        chapterPositionMs = progress.chapterPositionMs
        chapterDurationMs = progress.chapterDurationMs
        bookPositionMs = progress.bookPositionMs
        bookDurationMs = progress.bookDurationMs
    }

    var chapterRemainingMs: Int64 { max(0, chapterDurationMs - chapterPositionMs) }
    var bookRemainingMs: Int64 { max(0, bookDurationMs - bookPositionMs) }
    /// How far through the chapter, 0…1: the chapter panel's row bar.
    var chapterFraction: Float {
        chapterDurationMs > 0 ? min(1, max(0, Float(chapterPositionMs) / Float(chapterDurationMs))) : 0
    }
    var bookFraction: Float { bookDurationMs > 0 ? min(1, max(0, Float(bookPositionMs) / Float(bookDurationMs))) : 0 }
}
