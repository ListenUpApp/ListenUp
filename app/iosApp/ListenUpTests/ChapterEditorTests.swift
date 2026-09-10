import Testing
import Shared
@testable import ListenUp

/// The chapter editor's pure seams.
///
/// The observer's `apply(_:)` flatten needs a live `ChapterEditorViewModel` and lands at the
/// green-build pass, the same way `BookEditObserverTests` explains. What *is* pure and
/// constructible is the search filter, the row's playhead containment, and the refused-save
/// message — and those three are exactly where a wrong answer is invisible on screen.
@Suite("ChapterEditor")
struct ChapterEditorTests {
    private func row(_ number: Int, _ title: String, start: Int64 = 0, duration: Int64 = 1_000) -> EditableChapterRow {
        EditableChapterRow(
            id: "c\(number)",
            number: number,
            title: title,
            startMs: start,
            endMs: start + duration,
            isLocked: false
        )
    }

    private var threeRows: [EditableChapterRow] {
        [
            row(1, "The Boy Who Lived", start: 0, duration: 60_000),
            row(2, "The Vanishing Glass", start: 60_000, duration: 60_000),
            row(3, "The Letters from No One", start: 120_000, duration: 60_000)
        ]
    }

    // MARK: - Search

    /// A blank query is not a filter. Returning nothing for an empty box would empty the screen
    /// the instant someone focused it.
    @Test func blankQueryReturnsEverything() {
        #expect(ChapterListFilter.matching(threeRows, query: "").count == 3)
        #expect(ChapterListFilter.matching(threeRows, query: "   ").count == 3)
    }

    @Test func titleMatchIsCaseInsensitiveSubstring() {
        let found = ChapterListFilter.matching(threeRows, query: "vanishing")
        #expect(found.map(\.id) == ["c2"])
    }

    /// ⛔ Numbering survives the filter. The rows carry the number they hold in the FULL set, so a
    /// search cannot relabel chapter 3 as chapter 1 — which is the whole reason numbering happens
    /// before filtering rather than after.
    @Test func filteringNeverRenumbers() {
        let found = ChapterListFilter.matching(threeRows, query: "Letters")
        #expect(found.map(\.number) == [3])
    }

    /// ⛔ The number is the point of search on a 311-chapter book: "3" must find chapter 3, not
    /// every row whose title happens to contain a 3.
    @Test func exactNumberMatchesThatChapter() {
        let found = ChapterListFilter.matching(threeRows, query: "3")
        #expect(found.map(\.id) == ["c3"])
    }

    /// ⛔ Query "1", not "13". Against rows 1/13/31 a substring match and an exact match BOTH
    /// return only 13 for "13" — sabotage proved this spec passed against
    /// `"\(row.number)".contains(query)`. "1" is the query that separates them.
    @Test func numberMatchIsExactNotSubstring() {
        let rows = [row(1, "One"), row(13, "Thirteen"), row(31, "Thirty-one")]
        let found = ChapterListFilter.matching(rows, query: "1")
        #expect(found.map(\.number) == [1])
    }

    @Test func aQueryMatchingNothingReturnsNothing() {
        #expect(ChapterListFilter.matching(threeRows, query: "Elantris").isEmpty)
    }

    // MARK: - Which row the listener is inside

    /// Half-open: a boundary belongs to the chapter it OPENS, never to the one it closes. Both
    /// rows claiming the same instant is how two rows light up as "NOW" at once.
    @Test func aChapterHoldsItsOwnStartButNotItsEnd() {
        let second = threeRows[1]
        #expect(second.holds(60_000))
        #expect(second.holds(119_999))
        #expect(!second.holds(120_000))
        #expect(!second.holds(59_999))
    }

    @Test func exactlyOneRowHoldsAnyGivenPosition() {
        let holders = threeRows.filter { $0.holds(61_500) }
        #expect(holders.map(\.id) == ["c2"])
    }

    // MARK: - A refused save

    /// ⛔ The row's own number, not its index in the problem list. A refused save is only useful if
    /// it points at something the reader can see.
    @Test func aProblemNamesTheRowByItsNumber() {
        let problems: [ChapterSetProblem] = [ChapterSetProblemBlankTitle(chapterId: "c2")]
        let message = ChapterProblemText.message(for: problems, chapters: threeRows)
        #expect(message?.contains("2") == true)
    }

    /// ⛔ Only the first. One invalid boundary trips several rules at once, and listing them all
    /// describes the checker rather than the mistake.
    @Test func onlyTheFirstProblemIsReported() {
        let problems: [ChapterSetProblem] = [
            ChapterSetProblemBlankTitle(chapterId: "c1"),
            ChapterSetProblemNotStrictlyIncreasing(chapterId: "c3")
        ]
        let message = ChapterProblemText.message(for: problems, chapters: threeRows)
        #expect(message?.contains("3") == false)
    }

    /// A problem naming a chapter that has since been removed still says something, rather than
    /// resolving to a row that isn't there.
    @Test func aProblemAboutAVanishedChapterStillSaysSomething() {
        let problems: [ChapterSetProblem] = [ChapterSetProblemBlankTitle(chapterId: "gone")]
        #expect(ChapterProblemText.message(for: problems, chapters: threeRows) != nil)
    }

    @Test func noProblemsIsNoMessage() {
        #expect(ChapterProblemText.message(for: [], chapters: threeRows) == nil)
    }
}
