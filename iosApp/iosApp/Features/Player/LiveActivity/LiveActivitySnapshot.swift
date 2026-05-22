import Foundation

/// A plain value snapshot of the playback state the Live Activity needs.
/// Decouples `LiveActivityManager` and the content mapper from `PlayerCoordinator`,
/// keeping the mapping pure and testable.
struct LiveActivitySnapshot {
    let bookId: String
    let bookTitle: String
    let authorName: String
    let coverBlurHash: String?
    /// Local file path of the book's cover image, if available.
    let coverPath: String?
    let chapterTitle: String
    let isPlaying: Bool
    let bookPositionMs: Int64
    let bookDurationMs: Int64
    let chapterPositionMs: Int64
    let chapterDurationMs: Int64
}
