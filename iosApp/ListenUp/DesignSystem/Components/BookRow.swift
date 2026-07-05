import Foundation
import Shared

/// A native, value-typed projection of `BookListItem` for SwiftUI lists.
///
/// **Why this exists (performance, not cosmetics).** `BookListItem` is a Swift Export-bridged
/// Kotlin object. Handing thousands of them straight to a `ForEach` means every
/// SwiftUI diff, layout, and `ScrollViewReader.scrollTo` pass re-reads each item's
/// properties — and each read crosses the Kotlin boundary, converting a Kotlin UTF-16
/// string to a Swift one (`toKStringFromUtf16`). On a large library that re-bridging
/// saturated the main thread for tens of seconds: dragging the alphabet scrubber to an
/// off-screen section forced SwiftUI to realize every section above it, re-bridging
/// thousands of strings, and the app froze (Severe Hang in Instruments).
///
/// `BookRow` copies just the fields the grid renders into plain Swift values **once**,
/// at the observer boundary. SwiftUI then diffs cheap value types; realization and
/// sync-flood re-diffs never touch Kotlin again.
struct BookRow: Identifiable, Equatable, Hashable {
    let id: String
    let title: String
    let authorNames: String
    let hasDocuments: Bool
    let coverPath: String?
    let coverBlurHash: String?
    /// Content hash of the cover, threaded into `BookCoverImage` so a cover change busts the stale
    /// image cache (mirrors Android). Nil when unknown.
    let coverHash: String?
    /// Total duration in ms — for cards that show it (e.g. the contributor/series book rows).
    let duration: Int64
    /// The book's sequence within a series, when shown in a series context (e.g. Series detail).
    /// Nil in non-series contexts (Library grid, search), so it doesn't bloat those projections.
    let sequence: String?

    init(
        id: String,
        title: String,
        authorNames: String,
        hasDocuments: Bool,
        coverPath: String?,
        coverBlurHash: String?,
        coverHash: String? = nil,
        duration: Int64 = 0,
        sequence: String? = nil
    ) {
        self.id = id
        self.title = title
        self.authorNames = authorNames
        self.hasDocuments = hasDocuments
        self.coverPath = coverPath
        self.coverBlurHash = coverBlurHash
        self.coverHash = coverHash
        self.duration = duration
        self.sequence = sequence
    }

    /// Snapshot a Kotlin `BookListItem` into native Swift values. Reads each bridged
    /// property exactly once — the whole point is that SwiftUI never reads them again.
    /// `sequence` is series-context-specific, so it's passed in rather than read here.
    init(_ item: BookListItem, sequence: String? = nil) {
        self.id = item.idString
        self.title = item.title
        self.authorNames = item.authorNames
        self.hasDocuments = item.hasDocuments
        self.coverPath = item.coverPath
        self.coverBlurHash = item.coverBlurHash
        self.coverHash = item.coverHash
        self.duration = item.duration
        self.sequence = sequence
    }
}

/// Group books into alphabetically ordered sections for the Library grid + scrubber.
///
/// Titles starting with a non-letter collapse into a single `#` bucket that sorts to
/// the top. Order *within* a section is the incoming order (the shared ViewModel has
/// already sorted by title), which `Dictionary(grouping:)` preserves.
///
/// `ignoreArticles` must match the `ignoreTitleArticles` the shared ViewModel sorted with, so the
/// header letter agrees with the sort order (e.g. "The Hobbit" → "H", not "T"). See `TitleSorting`.
///
/// Pure and operating on `BookRow`, so grouping reads Swift strings — not re-bridged
/// Kotlin ones — and is unit-tested rather than buried in a `View`.
func bookSections(from books: [BookRow], ignoreArticles: Bool) -> [(letter: Character, books: [BookRow])] {
    let grouped = Dictionary(grouping: books) { book in
        TitleSorting.sortLetter(book.title, ignoreArticles: ignoreArticles)
    }

    return grouped.keys
        .sorted { lhs, rhs in
            if !lhs.isLetter && rhs.isLetter { return true }
            if lhs.isLetter && !rhs.isLetter { return false }
            return lhs < rhs
        }
        .map { (letter: $0, books: grouped[$0] ?? []) }
}
