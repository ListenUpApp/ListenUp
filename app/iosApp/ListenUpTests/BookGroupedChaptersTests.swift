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
        #expect(part("a", current: true).isOpen(toggled: []))
        #expect(!part("b").isOpen(toggled: []))
    }

    @Test func aReadersToggleFlipsAPartEitherWay() {
        #expect(!part("a", current: true).isOpen(toggled: ["a"]))
        #expect(part("b").isOpen(toggled: ["b"]))
    }

    /// ⛔ A closed part keeps its header and hides only its own chapters; those in no part always show.
    @Test func aClosedPartHidesItsChaptersOnly() {
        #expect(ids(grouped.visibleRows(toggled: [])) == ["chapter-1", "part-a", "chapter-3", "part-b"])
    }

    @Test func openingAPartShowsItsChapters() {
        #expect(ids(grouped.visibleRows(toggled: ["b"])).contains("chapter-5"))
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
