import Foundation
import ListenUpActivityKit

/// Pure mapping from a `LiveActivitySnapshot` to the Live Activity contract types.
/// No side effects — every output is a function of the input.
enum LiveActivityContentMapper {

    static func attributes(from snapshot: LiveActivitySnapshot) -> AudiobookActivityAttributes {
        AudiobookActivityAttributes(
            bookId: snapshot.bookId,
            bookTitle: snapshot.bookTitle,
            authorName: snapshot.authorName,
            coverBlurHash: snapshot.coverBlurHash
        )
    }

    static func contentState(from snapshot: LiveActivitySnapshot) -> AudiobookActivityAttributes.ContentState {
        let remainingMs = max(0, snapshot.bookDurationMs - snapshot.bookPositionMs)
        return AudiobookActivityAttributes.ContentState(
            chapterTitle: snapshot.chapterTitle,
            isPlaying: snapshot.isPlaying,
            bookProgress: fraction(snapshot.bookPositionMs, of: snapshot.bookDurationMs),
            chapterProgress: fraction(snapshot.chapterPositionMs, of: snapshot.chapterDurationMs),
            elapsedDescription: format(ms: snapshot.bookPositionMs),
            remainingDescription: format(ms: remainingMs) + " left"
        )
    }

    private static func fraction(_ value: Int64, of total: Int64) -> Double {
        total > 0 ? Double(value) / Double(total) : 0
    }

    /// "2h 14m" — the hour component is omitted below one hour.
    private static func format(ms: Int64) -> String {
        let totalSeconds = Int(ms / 1000)
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        return hours > 0 ? "\(hours)h \(minutes)m" : "\(minutes)m"
    }
}
