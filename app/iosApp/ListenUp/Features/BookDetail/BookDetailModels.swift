import Foundation

/// Download state for the UI, mapped from Kotlin's `BookDownloadState`.
enum DownloadUIState {
    case notDownloaded, queued, downloading, waitingForWifi, completed, partial, failed
}

/// A user shelf flattened for the shelf-picker sheet, with this book's membership.
struct ShelfRow: Identifiable, Equatable {
    let id: String
    let name: String
    let containsBook: Bool
}

/// A collection flattened for the collection-picker sheet (admin-only).
struct CollectionRow: Identifiable, Equatable {
    let id: String
    let name: String
}

/// The book fields the hero renders, projected to native values so the hero never
/// re-bridges the Kotlin `BookDetail` per SwiftUI diff (cover lookup + series-pill nav).
struct BookDetailHeaderModel: Equatable {
    let coverBookId: String
    let coverPath: String?
    /// Content hash of the current cover, folded into the cover's cache key so a re-scrape
    /// content-addresses the fresh cover instead of serving the stale id-stable local file.
    let coverHash: String?
    let seriesId: String?
}

/// What the screen does with one of the ViewModel's one-shot `BookDetailNavAction`s, as a native
/// value — so the mapping is testable without a live ViewModel behind it.
enum BookDetailNavReaction: Equatable {
    case openDocument(localPath: String)
    case showComingSoon
    /// The book was deleted from the server, folder and all: purge this device's copy and leave.
    case leaveDeletedBook
}
