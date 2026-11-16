import Shared
import Testing
@testable import ListenUp

/// How a marked book's outline becomes the cards the list draws. The grouping is the shared outline's; this pins
/// that the native projection reads it the same way Android and web do, and splits it one card per part.
@Suite("BookGroupedChapters")
struct BookGroupedChaptersTests {
    private func part(_ id: String, count: Int = 2) -> BookPartHeader {
        BookPartHeader(title: id, chapterId: id, chapterCount: count, durationMs: 1_000)
    }

    private func chapters(_ sections: [BookOutlineSection]) -> [[Int]] {
        sections.map { $0.chapters.map(\.index) }
    }

    // MARK: - Sections

    /// ⛔ Every chapter is a row: a part's header heads its card and never stands in for a chapter.
    @Test func eachPartIsACardOfEveryOneOfItsChapters() {
        let sections = BookGroupedChapters.sections([
            .part(part("a")), .chapter(index: 0, number: 1), .chapter(index: 1, number: 2),
            .part(part("b")), .chapter(index: 2, number: 3)
        ])
        #expect(sections.map(\.part?.chapterId) == ["a", "b"])
        #expect(chapters(sections) == [[0, 1], [2]])
    }

    /// Opening Credits before Part I is a card of its own, with no heading.
    @Test func chaptersBeforeTheFirstPartAreACardWithNoHeading() {
        let sections = BookGroupedChapters.sections([
            .chapter(index: 0, number: 1), .part(part("a")), .chapter(index: 1, number: 2)
        ])
        #expect(sections.count == 2)
        #expect(sections[0].part == nil && sections[0].book == nil)
        #expect(chapters(sections) == [[0], [1]])
    }

    /// A book's heading rides on the card of the part that opens it, not on an empty card of its own.
    @Test func aBookOpeningWithAPartIsOneCard() {
        let sections = BookGroupedChapters.sections([
            .book(title: "Book One", chapterId: "c0"), .part(part("a")), .chapter(index: 0, number: 1),
            .book(title: "Book Two", chapterId: "c1"), .chapter(index: 1, number: 1),
            .part(part("b")), .chapter(index: 2, number: 2)
        ])
        #expect(sections.map(\.book) == ["Book One", "Book Two", nil])
        #expect(sections.map(\.part?.chapterId) == ["a", nil, "b"])
        #expect(chapters(sections) == [[0], [1], [2]])
    }

    /// ⛔ An omnibus numbers each book from 1, so the number repeats; the index never does, and is the identity.
    @Test func aChapterIsKnownByItsIndexNotItsNumber() {
        let first = BookOutlineRow.chapter(index: 0, number: 1)
        let second = BookOutlineRow.chapter(index: 7, number: 1)
        #expect(first.id != second.id)
        let sections = BookGroupedChapters.sections([.book(title: "Two", chapterId: "c7"), second])
        #expect(sections.first?.chapters == [BookOutlineChapter(index: 7, number: 1)])
    }

    // MARK: - The heading

    @Test func aPartsHeadingSaysItsCountAndLength() {
        let reaper = BookPartHeader(title: "Part IV: Reaper", chapterId: "c", chapterCount: 13,
                                    durationMs: (5 * 60 + 16) * 60_000)
        #expect(reaper.summary.hasPrefix("13 chapters · "))
        #expect(reaper.spokenName == "Part IV: Reaper, 13 chapters, 5 hours, 16 minutes")
    }

    @Test func aPartOfOneChapterSaysSo() {
        #expect(part("a", count: 1).summary.hasPrefix("1 chapter · "))
    }

    // MARK: - Reading the shared outline

    /// ⛔ Through `init(_:)` and the real row mapping, every value distinct, so a swapped field fails.
    @Test func everySharedFieldLandsInItsNativeField() {
        let chapter = Chapter(id: "c", title: "Dawn", duration: 1, startTime: 2, partTitle: nil, bookTitle: nil)
        let shared = GroupedChapters(
            rows: [
                ChapterOutlineRowBookHeader(title: "Book One", chapterId: "b", startMs: 3),
                ChapterOutlineRowPartHeader(title: "Part I", chapterId: "p", chapterCount: 4, durationMs: 5,
                                            startMs: 6),
                ChapterOutlineRowChapterRow(chapter: chapter, index: 7, number: 8)
            ],
            previewRows: [ChapterOutlineRowChapterRow(chapter: chapter, index: 9, number: 10)]
        )

        let projected = BookGroupedChapters(shared)

        let header = BookPartHeader(title: "Part I", chapterId: "p", chapterCount: 4, durationMs: 5)
        #expect(projected.rows == [
            .book(title: "Book One", chapterId: "b"), .part(header), .chapter(index: 7, number: 8)
        ])
        #expect(projected.previewRows == [.chapter(index: 9, number: 10)])
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
