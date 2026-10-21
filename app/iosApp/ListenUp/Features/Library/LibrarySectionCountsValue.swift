import SwiftUI
import Shared

/// How many entries each Library section holds — the iPad sidebar's counts (spec §3.1.5) — as native values. Books is
/// the whole library whatever the status filter; the others are what each list shows.
struct LibrarySectionCountsValue: Equatable {
    let books: Int
    let series: Int
    let authors: Int
    let narrators: Int

    init(books: Int, series: Int, authors: Int, narrators: Int) {
        self.books = books
        self.series = series
        self.authors = authors
        self.narrators = narrators
    }

    /// Bridges the shared `LibrarySectionCounts`, reading each property once.
    init(_ counts: LibrarySectionCounts) {
        self.init(
            books: Int(counts.books),
            series: Int(counts.series),
            authors: Int(counts.authors),
            narrators: Int(counts.narrators)
        )
    }

    static let zero = LibrarySectionCountsValue(books: 0, series: 0, authors: 0, narrators: 0)

    func count(for section: LibraryTab) -> Int {
        switch section {
        case .books: books
        case .series: series
        case .authors: authors
        case .narrators: narrators
        }
    }
}

/// The one observable value the iPad sidebar reads for its counts, owned by `LibraryObserver`.
///
/// Its own object so the sidebar depends on the counts alone, and equality-guarded so an equal value never notifies:
/// the shared ViewModel re-emits the Library on every position save, and a sidebar that re-rendered with each one would
/// flicker for as long as a book plays.
@Observable
@MainActor
final class SectionCountsHolder {
    private(set) var counts: LibrarySectionCountsValue = .zero

    func assign(_ value: LibrarySectionCountsValue) {
        if counts != value { counts = value }
    }
}
