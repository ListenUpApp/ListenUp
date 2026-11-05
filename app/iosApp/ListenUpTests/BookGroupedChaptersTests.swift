import Shared
import Testing
@testable import ListenUp

/// Which rows a closed part hides. The grouping is the shared outline's; this pins that the native
/// projection reads it the same way Android and web do.
@Suite("BookGroupedChapters")
struct BookGroupedChaptersTests {
    private func part(_ id: String, current: Bool = false) -> BookPartHeader {
        BookPartHeader(title: id, chapterId: id, chapterCount: 2, durationMs: 1_000, startMs: 0,
                       containsCurrent: current, isCurrentChapter: false)
    }

    private var grouped: BookGroupedChapters {
        let rows: [BookOutlineRow] = [
            .chapter(number: 1, partId: nil),
            .part(part("a", current: true)),
            .chapter(number: 3, partId: "a"),
            .part(part("b")),
            .chapter(number: 5, partId: "b")
        ]
        return BookGroupedChapters(rows: rows, previewLabel: nil, previewRows: rows)
    }

    private func ids(_ rows: [BookOutlineRow]) -> [String] { rows.map(\.id) }

    @Test func thePartHoldingTheCurrentChapterOpensAndTheOthersDoNot() {
        #expect(part("a", current: true).isOpen(overrides: [:]))
        #expect(!part("b").isOpen(overrides: [:]))
    }

    /// ⛔ Tapping a chapter in an opened part moves the playhead into it. That must not close it.
    @Test func aPartTheReaderOpenedStaysOpenWhenPlaybackMovesIntoIt() {
        let overrides = part("b").toggled([:])
        #expect(part("b").isOpen(overrides: overrides))
        #expect(part("b", current: true).isOpen(overrides: overrides))
    }

    /// ⛔ And playback leaving a part the reader closed must not reopen it.
    @Test func aPartTheReaderClosedStaysClosedWhenPlaybackMovesOn() {
        let overrides = part("a", current: true).toggled([:])
        #expect(!part("a", current: true).isOpen(overrides: overrides))
        #expect(!part("a").isOpen(overrides: overrides))
    }

    @Test func aPartNobodyTouchedFollowsTheCurrentChapter() {
        #expect(part("a", current: true).isOpen(overrides: ["other": false]))
        #expect(!part("a").isOpen(overrides: ["other": true]))
    }

    @Test func togglingTwiceLandsWhereTheReaderLastLeftIt() {
        let overrides = part("b").toggled(part("b").toggled([:]))
        #expect(!part("b").isOpen(overrides: overrides))
        #expect(!part("b", current: true).isOpen(overrides: overrides))
    }

    /// ⛔ A closed part keeps its header and hides only its own chapters; those in no part always show.
    @Test func aClosedPartHidesItsChaptersOnly() {
        #expect(ids(grouped.visibleRows(overrides: [:])) == ["chapter-1", "part-a", "chapter-3", "part-b"])
    }

    @Test func openingAPartShowsItsChapters() {
        #expect(ids(grouped.visibleRows(overrides: ["b": true])).contains("chapter-5"))
    }

    // MARK: - Hairlines

    /// ⛔ The flat list draws none after its last row. A trailing one doubles the line above the
    /// "All N Chapters" button, and hangs off the end of a short book.
    @Test func noHairlineFollowsTheLastRow() {
        let rows = grouped.rows
        #expect(!BookGroupedChapters.hasDivider(after: rows[4], in: rows))
    }

    @Test func aHairlineFollowsEveryOtherChapterAndNoHeader() {
        let rows = grouped.rows
        #expect(BookGroupedChapters.hasDivider(after: rows[0], in: rows))
        #expect(BookGroupedChapters.hasDivider(after: rows[2], in: rows))
        #expect(!BookGroupedChapters.hasDivider(after: rows[1], in: rows))
    }

    // MARK: - Reading the shared outline

    /// ⛔ Through `init(_:)` and the real row mapping, every value distinct, so a swapped field fails.
    @Test func everySharedFieldLandsInItsNativeField() {
        let chapter = Chapter(id: "c", title: "Dawn", duration: 1, startTime: 2, partTitle: nil, bookTitle: nil)
        let shared = GroupedChapters(
            rows: [
                ChapterOutlineRowBookHeader(title: "Book One", chapterId: "b", startMs: 3),
                ChapterOutlineRowPartHeader(title: "Part I", chapterId: "p", chapterCount: 4, durationMs: 5, startMs: 6,
                                            containsCurrent: true, isCurrentChapter: false),
                ChapterOutlineRowChapterRow(chapter: chapter, number: 7, partId: "p")
            ],
            previewLabel: "Part I",
            previewRows: [ChapterOutlineRowChapterRow(chapter: chapter, number: 8, partId: nil)]
        )

        let projected = BookGroupedChapters(shared)

        let header = BookPartHeader(title: "Part I", chapterId: "p", chapterCount: 4, durationMs: 5, startMs: 6,
                                    containsCurrent: true, isCurrentChapter: false)
        #expect(projected.rows == [
            .book(title: "Book One", chapterId: "b"), .part(header), .chapter(number: 7, partId: "p")
        ])
        #expect(projected.previewLabel == "Part I")
        #expect(projected.previewRows == [.chapter(number: 8, partId: nil)])
    }

    @Test func aFoldedCurrentChapterIsNotReadAsMerelyContainingIt() {
        let shared = GroupedChapters(
            rows: [ChapterOutlineRowPartHeader(title: "Part I", chapterId: "p", chapterCount: 0, durationMs: 1, startMs: 2,
                                               containsCurrent: false, isCurrentChapter: true)],
            previewLabel: nil,
            previewRows: []
        )

        guard case .part(let part)? = BookGroupedChapters(shared).rows.first else {
            Issue.record("expected a part")
            return
        }
        #expect(part.isCurrentChapter)
        #expect(!part.containsCurrent)
    }

    @Test func aPartThatIsOnlyItsAnnouncementSaysJustItsLength() {
        let silent = BookPartHeader(title: "Part I", chapterId: "c", chapterCount: 0, durationMs: 3_600_000, startMs: 0,
                                    containsCurrent: false, isCurrentChapter: false)
        #expect(!silent.summary.contains("chapter"))
    }

    @Test func theResumeLineLeadsWithThePart() {
        let chapter = BookCurrentChapter(index: 6, number: 7, title: "5", startMs: 0, elapsedInChapterMs: 0,
                                         partTitle: "Part I")
        #expect(ResumeUnit.chapterText(chapter) == "Part I · Ch. 7 · 5")
    }

    @Test func aChapterOutsideAnyPartReadsAsItAlwaysHas() {
        let chapter = BookCurrentChapter(index: 5, number: 6, title: "Bridge Four", startMs: 0, elapsedInChapterMs: 0)
        #expect(ResumeUnit.chapterText(chapter) == "Ch. 6 · Bridge Four")
    }
}
