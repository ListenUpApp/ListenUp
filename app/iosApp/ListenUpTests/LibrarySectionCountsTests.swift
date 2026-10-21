import Observation
import Synchronization
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
        // onChange is @Sendable and fires on the mutating thread, so the flag is a Mutex, not a captured var.
        let notified = Mutex(false)
        withObservationTracking { _ = holder.counts } onChange: { notified.withLock { $0 = true } }
        holder.assign(LibrarySectionCountsValue(books: 1, series: 0, authors: 0, narrators: 0))
        #expect(!notified.withLock { $0 })
        holder.assign(LibrarySectionCountsValue(books: 2, series: 0, authors: 0, narrators: 0))
        #expect(notified.withLock { $0 })
    }
}
