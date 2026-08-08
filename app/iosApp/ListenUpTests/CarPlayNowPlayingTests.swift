import Testing
@testable import ListenUp

/// Pins the pure piece behind the CarPlay now-playing customizations: the chapter list the
/// Up Next button opens. The template wiring itself is CarPlay-framework glue and stays
/// untested, same as the browse templates.
@Suite("CarPlay now playing")
struct CarPlayNowPlayingTests {
    // ── chapter rows ──────────────────────────────────────────────────────────

    @Test func chaptersMapWithTheCurrentOneMarked() {
        let rows = CarPlayChapterRows.chapters(
            titles: ["Prologue", "The Long Road", "Epilogue"],
            currentIndex: 1
        )

        #expect(rows.map(\.title) == ["Prologue", "The Long Road", "Epilogue"])
        #expect(rows.map(\.isCurrent) == [false, true, false])
        #expect(rows.map(\.index) == [0, 1, 2])
    }

    /// Untitled chapters get the phone player's fallback name — 1-based, never a blank row.
    @Test func anUntitledChapterFallsBackToItsNumber() {
        let rows = CarPlayChapterRows.chapters(titles: [nil, "Named"], currentIndex: 0)

        #expect(rows[0].title == "Chapter 1")
        #expect(rows[1].title == "Named")
    }

    @Test func aChapterlessBookProducesNoRows() {
        #expect(CarPlayChapterRows.chapters(titles: [], currentIndex: 0).isEmpty)
    }
}
