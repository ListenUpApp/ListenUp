import Foundation
import Testing
@testable import ListenUp

/// The "Merged into this" list's sentences (#1061) — what a row says about a merge, and what an
/// undo says it put back. Pure, so they are pinned here rather than read off a rendered view.
@Suite("MergeHistory")
struct MergeHistoryTests {
    private func row(books: Int, by name: String?) -> MergeRow {
        MergeRow(
            id: "r1",
            sourceName: "Stormlite",
            mergedAt: Date(timeIntervalSince1970: 1_700_000_000),
            mergedByName: name,
            bookCount: books
        )
    }

    @Test func aRowSaysHowManyBooksAndWhoMergedIt() {
        let detail = MergeHistoryText.detail(row(books: 4, by: "Simon"))

        #expect(detail.hasPrefix("Up to 4 books · "))
        #expect(detail.hasSuffix(" · by Simon"))
    }

    /// A departed account leaves no name rather than a dangling "by".
    @Test func aMergeByAGoneAccountNamesNoOne() {
        let detail = MergeHistoryText.detail(row(books: 1, by: nil))

        #expect(detail.hasPrefix("Up to 1 book · "))
        #expect(!detail.contains("by "))
    }

    @Test func anOutcomeSaysWhatCameBackAndWhatStayed() {
        let lines = MergeHistoryText.outcome(
            MergeOutcomeModel(sourceName: "Stormlite", booksRestored: 3, booksSkipped: 1, restoredAtTopLevel: false)
        )

        #expect(lines == [
            "\u{201C}Stormlite\u{201D} is back. 3 books moved back.",
            "1 book had changed since and stayed where it was."
        ])
    }

    @Test func aGenreWhoseParentIsGoneSaysWhereItLanded() {
        let lines = MergeHistoryText.outcome(
            MergeOutcomeModel(sourceName: "Scifi", booksRestored: 1, booksSkipped: 0, restoredAtTopLevel: true)
        )

        #expect(lines == [
            "\u{201C}Scifi\u{201D} is back. 1 book moved back.",
            "Its old parent is gone, so it's at the top level now."
        ])
    }
}
