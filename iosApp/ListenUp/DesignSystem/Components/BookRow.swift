import Foundation
@preconcurrency import Shared

/// A native, value-typed projection of `BookListItem` for SwiftUI lists.
///
/// **Why this exists (performance, not cosmetics).** `BookListItem` is a SKIE-bridged
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

    init(
        id: String,
        title: String,
        authorNames: String,
        hasDocuments: Bool,
        coverPath: String?,
        coverBlurHash: String?
    ) {
        self.id = id
        self.title = title
        self.authorNames = authorNames
        self.hasDocuments = hasDocuments
        self.coverPath = coverPath
        self.coverBlurHash = coverBlurHash
    }

    /// Snapshot a Kotlin `BookListItem` into native Swift values. Reads each bridged
    /// property exactly once — the whole point is that SwiftUI never reads them again.
    init(_ item: BookListItem) {
        self.id = item.idString
        self.title = item.title
        self.authorNames = item.authorNames
        self.hasDocuments = item.hasDocuments
        self.coverPath = item.coverPath
        self.coverBlurHash = item.coverBlurHash
    }
}

/// Group books into alphabetically ordered sections for the Library grid + scrubber.
///
/// Titles starting with a non-letter collapse into a single `#` bucket that sorts to
/// the top. Order *within* a section is the incoming order (the shared ViewModel has
/// already sorted by title), which `Dictionary(grouping:)` preserves.
///
/// Pure and operating on `BookRow`, so grouping reads Swift strings — not re-bridged
/// Kotlin ones — and is unit-tested rather than buried in a `View`.
func bookSections(from books: [BookRow]) -> [(letter: Character, books: [BookRow])] {
    let grouped = Dictionary(grouping: books) { book -> Character in
        guard let first = book.title.first?.uppercased().first else { return "#" }
        return first.isLetter ? first : "#"
    }

    return grouped.keys
        .sorted { lhs, rhs in
            if !lhs.isLetter && rhs.isLetter { return true }
            if lhs.isLetter && !rhs.isLetter { return false }
            return lhs < rhs
        }
        .map { (letter: $0, books: grouped[$0] ?? []) }
}
