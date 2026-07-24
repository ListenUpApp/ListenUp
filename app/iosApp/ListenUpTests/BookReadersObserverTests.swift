import Foundation
import Testing
import Shared
@testable import ListenUp

/// Pure mapping tests for `BookReadersObserver`. These construct the KMP `Reader`/`BookReaders`
/// domain types directly (exported to `Shared`) and exercise the testable `BookReaderRow`
/// projection and `rows(from:)` helper — no live ViewModel or flow needed.
@Suite("Book readers mapping")
struct BookReadersObserverTests {

    private func reader(
        id: String,
        name: String,
        isYou: Bool = false,
        progressPct: Int? = nil,
        finishes: [Int64] = []
    ) -> Reader {
        // Native Swift Export bridges Kotlin `Int?` → `Int32?` and `List<Long>` → `[Int64]`.
        Reader(
            userId: id,
            displayName: name,
            isYou: isYou,
            currentProgressPct: progressPct.map { Int32($0) },
            finishes: finishes
        )
    }

    @Test func readingReaderMapsProgressAndFlag() {
        let row = BookReaderRow(from: reader(id: "u1", name: "Marcus Lee", progressPct: 62))

        #expect(row.id == "u1")
        #expect(row.displayName == "Marcus Lee")
        #expect(row.initials == "ML")
        #expect(row.isYou == false)
        #expect(row.isReading == true)
        #expect(row.progressPercent == 62)
        #expect(row.lastFinished == nil)
    }

    @Test func finishedReaderMapsMostRecentDateAndIsNotReading() {
        // 2024-04-01T00:00:00Z = 1_711_929_600_000 ms (newest), plus an older finish.
        let row = BookReaderRow(from: reader(
            id: "u2",
            name: "David Warren",
            finishes: [1_711_929_600_000, 1_600_000_000_000]
        ))

        #expect(row.isReading == false)
        #expect(row.progressPercent == nil)
        // `finishes` is newest-first; the row takes the first (most recent).
        #expect(row.lastFinished == Date(timeIntervalSince1970: 1_711_929_600))
    }

    @Test func currentUserFlagSurvives() {
        let row = BookReaderRow(from: reader(id: "me", name: "Simon Hull", isYou: true, progressPct: 38))
        #expect(row.isYou == true)
    }

    @Test func singleWordNameYieldsSingleInitial() {
        #expect(BookReaderRow.initials(from: "Cher") == "C")
        #expect(BookReaderRow.initials(from: "").isEmpty)
    }

    @Test func rowsMapsEveryReaderPreservingOrder() {
        let readers = BookReaders(readers: [
            reader(id: "u1", name: "Marcus Lee", progressPct: 62),
            reader(id: "u2", name: "David Warren", finishes: [1_711_929_600_000])
        ])

        let rows = BookReadersObserver.rows(from: readers)

        #expect(rows.map(\.id) == ["u1", "u2"])
        #expect(rows[0].isReading == true)
        #expect(rows[1].isReading == false)
    }

    @Test func emptyReadersYieldsNoRows() {
        #expect(BookReadersObserver.rows(from: BookReaders(readers: [])).isEmpty)
    }
}
