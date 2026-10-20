import Observation
import Shared
import Testing
@testable import ListenUp

/// The iPad sidebar's counts: bridged once per content revision, and silent when nothing changed.
@Suite("Library section counts")
struct LibrarySectionCountsTests {
    @Test func bridgesTheFourCounts() {
        let value = LibrarySectionCountsValue(LibrarySectionCounts(books: 248, series: 42, authors: 132, narrators: 97))
        #expect(value == LibrarySectionCountsValue(books: 248, series: 42, authors: 132, narrators: 97))
        #expect(value.count(for: .series) == 42)
        #expect(value.count(for: .narrators) == 97)
    }

    /// The sidebar re-renders only when a count really changes: an equal value must not notify observers.
    @MainActor @Test func anEqualValueDoesNotNotify() {
        let holder = SectionCountsHolder()
        holder.assign(LibrarySectionCountsValue(books: 1, series: 0, authors: 0, narrators: 0))
        var notified = false
        withObservationTracking { _ = holder.counts } onChange: { notified = true }
        holder.assign(LibrarySectionCountsValue(books: 1, series: 0, authors: 0, narrators: 0))
        #expect(!notified)
        holder.assign(LibrarySectionCountsValue(books: 2, series: 0, authors: 0, narrators: 0))
        #expect(notified)
    }
}
