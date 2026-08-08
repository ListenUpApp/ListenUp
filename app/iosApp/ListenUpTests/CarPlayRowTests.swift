import Testing
@testable import ListenUp

/// Rows shown in the car are built from the same app data the phone shows, mapped to a native
/// value type at the boundary. These tests pin that mapping — the templates themselves are not
/// unit-testable, so every decision worth checking lives here rather than in the delegate.
@Suite("CarPlayRow")
struct CarPlayRowTests {
    // ── continue listening ────────────────────────────────────────────────────

    @Test func aReadyItemBecomesAPlayableRowWithAuthorAndTimeLeft() {
        let rows = CarPlayRows.continueListening(from: [item(id: "b1", title: "The Way of Kings")])

        #expect(rows.count == 1)
        #expect(rows[0].id == "b1")
        #expect(rows[0].title == "The Way of Kings")
        #expect(rows[0].detailText == "Brandon Sanderson • 12h 4m left")
        #expect(rows[0].isPlayable)
    }

    /// The cover path rides along so the car can show the same artwork the phone shelf shows;
    /// a book with no cached cover maps to `nil` and the row simply renders text-only.
    @Test func theCoverPathRidesAlongForArtwork() {
        let rows = CarPlayRows.continueListening(from: [
            item(id: "b1", coverPath: "/covers/b1.jpg"),
            item(id: "b2")
        ])

        #expect(rows[0].coverPath == "/covers/b1.jpg")
        #expect(rows[1].coverPath == nil)
    }

    /// A book with no remaining-time string still names its author rather than trailing a
    /// separator into nothing.
    @Test func aMissingTimeLeftLeavesNoDanglingSeparator() {
        let rows = CarPlayRows.continueListening(from: [item(id: "b1", timeLeft: "")])

        #expect(rows[0].detailText == "Brandon Sanderson")
    }

    @Test func aMissingAuthorFallsBackToTimeLeftAlone() {
        let rows = CarPlayRows.continueListening(from: [item(id: "b1", author: "")])

        #expect(rows[0].detailText == "12h 4m left")
    }

    /// Loading placeholders are skeleton cards on the phone. In a car they would be blank,
    /// untappable rows, so they are dropped rather than rendered.
    @Test func loadingPlaceholdersAreDropped() {
        let rows = CarPlayRows.continueListening(from: [
            item(id: "b1"),
            loadingItem(id: "b2"),
            item(id: "b3")
        ])

        #expect(rows.map(\.id) == ["b1", "b3"])
    }

    /// Mirrors Android Auto's `CONTINUE_LISTENING_LIMIT` — a curated shelf, not a truncated
    /// library. Keeping the two surfaces at the same number is deliberate.
    @Test func theShelfIsCappedAtTheCuratedLimit() {
        let many = (1...20).map { item(id: "b\($0)") }

        let rows = CarPlayRows.continueListening(from: many)

        #expect(rows.count == CarPlayRows.continueListeningLimit)
        #expect(CarPlayRows.continueListeningLimit == 8)
        #expect(rows.first?.id == "b1")
    }

    /// The cap must apply to what a driver can actually see — dropping placeholders first, so a
    /// shelf padded with in-flight items still offers eight real books.
    @Test func theCapCountsRealBooksNotPlaceholders() {
        let padded = (1...6).map { loadingItem(id: "loading\($0)") } + (1...10).map { item(id: "b\($0)") }

        let rows = CarPlayRows.continueListening(from: padded)

        #expect(rows.count == 8)
        #expect(rows.first?.id == "b1")
    }

    @Test func anEmptyShelfProducesNoRows() {
        #expect(CarPlayRows.continueListening(from: []).isEmpty)
    }

    // ── library ───────────────────────────────────────────────────────────────

    @Test func aLibraryBookBecomesAPlayableRowWithAuthorAndCover() {
        let rows = CarPlayRows.library(
            from: [bookRow(id: "b1", title: "Warbreaker", coverPath: "/covers/b1.jpg")],
            limit: 10
        )

        #expect(rows.count == 1)
        #expect(rows[0].id == "b1")
        #expect(rows[0].title == "Warbreaker")
        #expect(rows[0].detailText == "Brandon Sanderson")
        #expect(rows[0].coverPath == "/covers/b1.jpg")
        #expect(rows[0].isPlayable)
    }

    /// The head unit caps how many rows a list may hold; the mapping enforces the cap so the
    /// delegate never hands CarPlay an over-long section.
    @Test func theLibraryIsCappedAtTheHeadUnitsLimit() {
        let many = (1...30).map { bookRow(id: "b\($0)") }

        let rows = CarPlayRows.library(from: many, limit: 25)

        #expect(rows.count == 25)
        #expect(rows.first?.id == "b1")
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private func item(
        id: String,
        title: String = "A Book",
        author: String = "Brandon Sanderson",
        timeLeft: String = "12h 4m left",
        coverPath: String? = nil
    ) -> ContinueItem {
        ContinueItem(
            id: id,
            title: title,
            author: author,
            coverPath: coverPath,
            coverHash: nil,
            progress: 0.3,
            progressPercent: 30,
            timeLeft: timeLeft,
            isLoading: false
        )
    }

    private func bookRow(
        id: String,
        title: String = "A Book",
        author: String = "Brandon Sanderson",
        coverPath: String? = nil
    ) -> BookRow {
        BookRow(id: id, title: title, authorNames: author, hasDocuments: false, coverPath: coverPath)
    }

    private func loadingItem(id: String) -> ContinueItem {
        ContinueItem(
            id: id,
            title: "",
            author: "",
            coverPath: nil,
            coverHash: nil,
            progress: 0,
            progressPercent: 0,
            timeLeft: "",
            isLoading: true
        )
    }
}
