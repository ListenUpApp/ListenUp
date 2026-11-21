import Shared

/// A started, unfinished book's progress, projected once at an observer from the shared `BookProgress.InProgress`.
/// The bar draws `fraction`, a label prints `percent` and a time-left reads `remainingMs`: none of them is ever
/// worked out again here, so a book reads the same percent on every screen and every platform.
struct ListeningProgress: Equatable {
    let fraction: Float
    let percent: Int
    let remainingMs: Int64

    init(fraction: Float, percent: Int, remainingMs: Int64) {
        self.fraction = fraction
        self.percent = percent
        self.remainingMs = remainingMs
    }

    init(_ progress: BookProgressInProgress) {
        self.init(fraction: progress.fraction, percent: Int(progress.percent), remainingMs: progress.remainingMs)
    }

    /// A `Map<BookId, BookProgress.InProgress>` keyed by the book-id string the screens look up by.
    static func byBookId(_ raw: [BookId: BookProgressInProgress]) -> [String: ListeningProgress] {
        var result: [String: ListeningProgress] = [:]
        for (key, value) in raw {
            result[key.value] = ListeningProgress(value)
        }
        return result
    }
}
