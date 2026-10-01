import Foundation
import Testing
import Shared
@testable import ListenUp

// Find on Hardcover's boundary mappings: every shared state and event lands on its native value, the
// results split into "By {author}" and the rest, and the tells (audiobook, year, ratings) read as words.

@Suite("Find on Hardcover mapping")
struct HardcoverMatchObserverTests {
    private func row(
        id: Int64,
        title: String,
        authors: [String] = ["Andy Weir"],
        year: Int32? = 2021,
        ratings: Int32? = 47_312,
        audiobook: Bool = true,
        sharesAuthor: Bool = true
    ) -> HardcoverCandidateRow {
        HardcoverCandidateRow(
            hcBookId: id, hcEditionId: audiobook ? 9_001 : nil, title: title, authors: authors,
            releaseYear: year, ratingsCount: ratings, hasAudiobookEdition: audiobook, sharesAuthor: sharesAuthor
        )
    }

    private func ready(
        _ search: HardcoverSearchState,
        current: HardcoverMatchedBook? = nil,
        linkingId: Int64? = nil,
        suggestions: [String] = []
    ) -> HardcoverMatchUiStateReady {
        HardcoverMatchUiStateReady(
            bookId: "b1", bookTitle: "Project Hail Mary", bookAuthors: "Andy Weir", coverPath: nil, coverHash: nil,
            query: "Project Hail Mary", search: search, currentMatch: current, linkingId: linkingId,
            isRemoving: false, suggestions: suggestions
        )
    }

    private func model(_ state: HardcoverMatchUiState) -> HardcoverMatchModel? {
        guard case .ready(let model) = HardcoverMatchObserver.phase(from: state) else {
            Issue.record("expected .ready")
            return nil
        }
        return model
    }

    @Test func resultsSplitIntoTheBooksAuthorAndTheRest() {
        let real = row(id: 1, title: "Project Hail Mary")
        let summary = row(
            id: 2, title: "Summary of Project Hail Mary", authors: [], ratings: 3, audiobook: false, sharesAuthor: false
        )
        let search = HardcoverMatchObserver.searchPhase(from: HardcoverSearchStateResults(rows: [real, summary]))
        guard case .results(let byAuthor, let others) = search else {
            Issue.record("expected .results")
            return
        }
        #expect(byAuthor.map(\.id) == [1])
        #expect(others.map(\.id) == [2])
    }

    @Test func aResultCarriesItsTells() {
        let candidate = HardcoverMatchObserver.candidate(
            from: row(id: 1, title: "Project Hail Mary", authors: ["Andy Weir", "Ray Porter", "Someone"])
        )
        #expect(candidate == HardcoverCandidate(
            id: 1,
            title: "Project Hail Mary",
            authors: "Andy Weir, Ray Porter",
            detail: "\(String(localized: "hardcover.match_audiobook")) · 2021",
            ratings: String(format: String(localized: "hardcover.match_ratings"), 47_312.formatted()),
            sharesAuthor: true
        ))
    }

    @Test func aResultWithNoCreditsOrDetailsSaysSo() {
        let candidate = HardcoverMatchObserver.candidate(
            from: row(
                id: 2, title: "Study Guide", authors: [], year: nil, ratings: nil, audiobook: false, sharesAuthor: false
            )
        )
        #expect(candidate.authors == String(localized: "hardcover.match_unknown_author"))
        #expect(candidate.detail == nil)
        #expect(candidate.ratings == String(localized: "hardcover.match_ratings_none"))
    }

    @Test func ratingsReadNaturally() {
        #expect(HardcoverMatchObserver.ratingsText(nil) == String(localized: "hardcover.match_ratings_none"))
        #expect(HardcoverMatchObserver.ratingsText(0) == String(localized: "hardcover.match_ratings_none"))
        #expect(HardcoverMatchObserver.ratingsText(1) == String(localized: "hardcover.match_ratings_one"))
        #expect(HardcoverMatchObserver.ratingsText(212)
            == String(format: String(localized: "hardcover.match_ratings"), "212"))
    }

    @Test func searchingAndNothingFoundMapDirectly() {
        #expect(HardcoverMatchObserver.searchPhase(from: HardcoverSearchStateSearching.shared) == .searching)
        #expect(HardcoverMatchObserver.searchPhase(from: HardcoverSearchStateNoResults.shared) == .noResults)
    }

    @Test func aCurrentMatchShowsWithItsByline() {
        let current = HardcoverMatchedBook(
            hcBookId: 427_578, title: "Project Hail Mary", authors: ["Andy Weir"], releaseYear: 2021,
            chosenByYou: false, hcEditionId: nil, method: nil
        )
        #expect(model(ready(HardcoverSearchStateSearching.shared, current: current))?.current
            == HardcoverCurrentMatch(title: "Project Hail Mary", byline: "Andy Weir · 2021"))
    }

    @Test func anUnnamedMatchStillReadsAsMatched() {
        let current = HardcoverMatchedBook(
            hcBookId: 1, title: nil, authors: [], releaseYear: nil, chosenByYou: true, hcEditionId: nil, method: nil
        )
        #expect(HardcoverMatchObserver.currentMatch(from: current)
            == HardcoverCurrentMatch(title: String(localized: "hardcover.book_row_matched_unnamed"), byline: nil))
    }

    @Test func aLinkInFlightKeepsEveryActionWaiting() {
        let model = model(ready(HardcoverSearchStateSearching.shared, linkingId: 7))
        #expect(model?.linkingId == 7)
        #expect(model?.isBusy == true)
    }

    @Test func resultsNoneByTheAuthorWithABetterSearchAreWeak() {
        let other = row(id: 3, title: "Hail Mary", authors: ["Someone Else"], sharesAuthor: false)
        let weak = model(
            ready(HardcoverSearchStateResults(rows: [other]), suggestions: ["Project Hail Mary Andy Weir"])
        )
        #expect(weak?.isWeak == true)
        #expect(weak?.leadAuthor == "Andy Weir")
        #expect(weak?.suggestions == ["Project Hail Mary Andy Weir"])

        let strong = model(ready(HardcoverSearchStateResults(rows: [row(id: 1, title: "Project Hail Mary")])))
        #expect(strong?.isWeak == false)
    }

    @Test func bookMissingAndLoadingMapDirectly() {
        #expect(HardcoverMatchObserver.phase(from: HardcoverMatchUiStateBookMissing.shared) == .bookMissing)
        #expect(HardcoverMatchObserver.phase(from: HardcoverMatchUiStateLoading.shared) == .loading)
    }

    @Test func linkedAndRemovedCloseTheSheet() {
        let linked = HardcoverMatchEventLinked(picked: row(id: 1, title: "Project Hail Mary"), replaced: nil)
        #expect(HardcoverMatchObserver.effect(of: linked) == .dismiss)
        #expect(HardcoverMatchObserver.effect(of: HardcoverMatchEventMatchRemoved.shared) == .dismiss)
    }

    @Test func anErrorBecomesAnAlertWithItsMessage() {
        let error = ServerConnectErrorInvalidUrl(correlationId: nil, debugInfo: nil, reason: "bad url")
        #expect(HardcoverMatchObserver.effect(of: HardcoverMatchEventShowError(error: error)) == .alert(error.message))
    }
}
