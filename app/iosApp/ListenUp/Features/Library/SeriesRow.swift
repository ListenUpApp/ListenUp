import Foundation
import Shared

/// A native, value-typed projection of `SeriesWithBooks` for the Library Series tab.
///
/// **Why (performance — see `BookRow`).** Feeding Swift Export-bridged `SeriesWithBooks` straight into a
/// `ForEach` re-bridges every property on every SwiftUI diff/layout/`scrollTo`. The Series cards were
/// the worst case: `SeriesRowCard`/`SeriesGridCard` mapped *every nested book* to a cover via
/// `CoverStack(books:)` on each pass. Snapshotting once here — including the ≤5 covers the deck needs
/// — means SwiftUI diffs cheap Swift values and the alphabet scrubber stops hanging the main thread.
struct SeriesRow: Identifiable, Equatable, Hashable {
    let id: String
    let name: String
    let bookCount: Int
    /// First book's first author, used in the row's meta line (e.g. "12 books · Sanderson").
    let authorName: String?
    /// Up to 5 precomputed covers (the iPad grid shows 5, the iPhone row 4).
    let covers: [CoverArt]

    init(id: String, name: String, bookCount: Int, authorName: String?, covers: [CoverArt]) {
        self.id = id
        self.name = name
        self.bookCount = bookCount
        self.authorName = authorName
        self.covers = covers
    }

    /// Snapshot a Kotlin `SeriesWithBooks` into native values. Reads each bridged property once.
    init(_ series: SeriesWithBooks) {
        let books = Array(series.books)
        self.id = series.series.idString
        self.name = series.series.name
        self.bookCount = books.count
        self.authorName = books.first?.authors.first?.name
        self.covers = books.prefix(5).map(CoverArt.init(book:))
    }
}

/// Alphabet index for the Series scrubber: the first series id in each first-letter bucket, in
/// first-seen order. Pure and over `SeriesRow` (cheap Swift strings) so it's unit-tested and never
/// re-bridges. Mirrors the books grid's `bookSections`. `firstId` is the scroll target id used by
/// `SeriesContent` (`"series-<id>"`).
func seriesAlphabetIndex(from series: [SeriesRow], ignoreArticles: Bool) -> [(letter: String, firstId: String)] {
    var index: [(letter: String, firstId: String)] = []
    var seen: Set<String> = []
    for row in series {
        let letter = String(TitleSorting.sortLetter(row.name, ignoreArticles: ignoreArticles))
        if !seen.contains(letter) {
            seen.insert(letter)
            index.append((letter: letter, firstId: "series-\(row.id)"))
        }
    }
    return index
}
