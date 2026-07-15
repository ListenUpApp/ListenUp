import Foundation

#if os(iOS)
import ActivityKit

/// The Live Activity contract for audiobook playback — the single definition
/// shared by the app and the widget extension.
///
/// Static attributes are set once when the activity starts; `ContentState`
/// updates as playback progresses.
///
/// `ActivityAttributes` (and ActivityKit's type surface) is `@available(macOS, unavailable)`,
/// so this entire type is gated on `os(iOS)` to let the package `swift build` on macOS
/// where the future cross-platform app target lives. `canImport(ActivityKit)` doesn't work
/// here because the framework itself exists on macOS — only its types are unavailable.
public struct AudiobookActivityAttributes: ActivityAttributes {

    /// Static data — set when the activity starts.
    public let bookId: String
    public let bookTitle: String
    public let authorName: String

    public init(bookId: String, bookTitle: String, authorName: String) {
        self.bookId = bookId
        self.bookTitle = bookTitle
        self.authorName = authorName
    }

    /// Dynamic data — pushed on discrete playback events.
    public struct ContentState: Codable, Hashable, Sendable {
        public let chapterTitle: String
        public let isPlaying: Bool
        public let bookProgress: Double      // 0.0–1.0, whole book
        public let chapterProgress: Double   // 0.0–1.0, current chapter
        public let elapsedDescription: String   // e.g. "2h 14m"
        public let remainingDescription: String // e.g. "4h 32m left"

        public init(
            chapterTitle: String,
            isPlaying: Bool,
            bookProgress: Double,
            chapterProgress: Double,
            elapsedDescription: String,
            remainingDescription: String
        ) {
            self.chapterTitle = chapterTitle
            self.isPlaying = isPlaying
            self.bookProgress = bookProgress
            self.chapterProgress = chapterProgress
            self.elapsedDescription = elapsedDescription
            self.remainingDescription = remainingDescription
        }
    }
}
#endif
