import Foundation
import Testing
import Shared
@testable import ListenUp

/// Pure tests for rating a book on iOS: the observer's projection of `BookRatingsUiState`, the
/// stars' hit-testing and VoiceOver stepping, the note's UTF-16 cap, and the rating riding on
/// each reader's row. No live ViewModel or flow needed.
@Suite("Book ratings")
struct BookRatingsTests {

    // MARK: - Fixtures

    private func rating(halfStars: Int, note: String? = nil, userId: String = "u1") -> ListenerRating {
        ListenerRating(
            bookId: "b1",
            userId: userId,
            halfStars: Int32(halfStars),
            note: note,
            ratedAtMs: 1_711_929_600_000
        )
    }

    private func reader(
        id: String,
        name: String,
        progressPct: Int? = nil,
        finishes: [Int64] = [],
        rating: ListenerRating? = nil
    ) -> Reader {
        Reader(
            userId: id,
            displayName: name,
            isYou: false,
            currentProgressPct: progressPct.map { Int32($0) },
            finishes: finishes,
            rating: rating,
            hardcoverFinishes: [],
            finishesAlsoOnHardcover: []
        )
    }

    // MARK: - Observer mapping

    @Test func loadingMapsToLoading() {
        #expect(BookRatingsObserver.phase(from: BookRatingsUiStateLoading.shared) == .loading)
    }

    private func externalRating(source: ExternalRatingSource, average: Double, count: Int) -> ExternalRating {
        ExternalRating(source: source, average: average, count: Int32(count))
    }

    @Test func readyWithNobodyRatingHasNoAverageAndNoRatingOfMine() {
        let phase = BookRatingsObserver.phase(from: BookRatingsUiStateReady(
            listeners: nil,
            mine: nil,
            external: nil,
            breakdown: [],
            canRefresh: false,
            isRefreshingExternal: false
        ))

        #expect(phase == .ready(BookRatingsSnapshot(
            listeners: nil,
            mine: nil,
            external: nil,
            breakdown: [],
            canRefresh: false,
            isRefreshingExternal: false
        )))
    }

    @Test func readyCarriesTheAverageAndMyRatingAsNativeValues() {
        let state = BookRatingsUiStateReady(
            listeners: ListenerAverage(averageHalfStars: 7.5, count: 3),
            mine: rating(halfStars: 7, note: "Loved the narrator"),
            external: nil,
            breakdown: [],
            canRefresh: false,
            isRefreshingExternal: false
        )

        let phase = BookRatingsObserver.phase(from: state)

        #expect(phase == .ready(BookRatingsSnapshot(
            listeners: ListenersAverage(averageHalfStars: 7.5, count: 3),
            mine: MyRating(halfStars: 7, note: "Loved the narrator"),
            external: nil,
            breakdown: [],
            canRefresh: false,
            isRefreshingExternal: false
        )))
    }

    @Test func readyMapsTheExternalScoreAndBreakdownAndCanRefreshAndIsRefreshing() {
        let state = BookRatingsUiStateReady(
            listeners: nil,
            mine: nil,
            external: CombinedScore(
                average: 4.4,
                count: 12_000,
                outsideShares: [
                    OutsideShare(source: .audible, share: 0.6),
                    OutsideShare(source: .goodreads, share: 0.4)
                ],
                listenersShare: nil
            ),
            breakdown: [
                externalRating(source: .audible, average: 4.5, count: 8_100),
                externalRating(source: .goodreads, average: 4.1, count: 3_900)
            ],
            canRefresh: true,
            isRefreshingExternal: true
        )

        let phase = BookRatingsObserver.phase(from: state)

        #expect(phase == .ready(BookRatingsSnapshot(
            listeners: nil,
            mine: nil,
            external: ExternalScore(
                average: 4.4,
                count: 12_000,
                outsideShares: [.audible: 0.6, .goodreads: 0.4],
                listenersShare: nil
            ),
            breakdown: [
                ExternalRatingRow(source: .audible, average: 4.5, count: 8_100),
                ExternalRatingRow(source: .goodreads, average: 4.1, count: 3_900)
            ],
            canRefresh: true,
            isRefreshingExternal: true
        )))
    }

    @Test func readyCarriesTheListenersShareOfTheScore() {
        let state = BookRatingsUiStateReady(
            listeners: ListenerAverage(averageHalfStars: 8, count: 3),
            mine: nil,
            external: CombinedScore(
                average: 4.3,
                count: 1_010,
                outsideShares: [OutsideShare(source: .audible, share: 0.8)],
                listenersShare: 0.2
            ),
            breakdown: [externalRating(source: .audible, average: 4.7, count: 1_007)],
            canRefresh: false,
            isRefreshingExternal: false
        )

        guard case .ready(let snapshot) = BookRatingsObserver.phase(from: state) else {
            Issue.record("expected a ready phase")
            return
        }
        #expect(snapshot.external?.outsideShares == [.audible: 0.8])
        #expect(snapshot.external?.listenersShare == 0.2)
        #expect(snapshot.external?.sourceCount == 2)
    }

    // MARK: - Headline text (averageLabel + compactCount, NOT starsLabel — starsLabel rounds to a
    // half-star, which read wrong for the outside-world headline)

    @Test func externalHeadlineShowsOneDecimalAndCompactCount() {
        let headline = BookRatingSection.externalHeadline(ExternalScore(average: 4.4, count: 12_000))
        #expect(headline == "\u{2605} 4.4 · 12k ratings")
    }

    @Test func externalHeadlineShowsAWholeNumberWithATrailingZero() {
        let headline = BookRatingSection.externalHeadline(ExternalScore(average: 4.0, count: 812))
        #expect(headline == "\u{2605} 4.0 · 812 ratings")
    }

    @Test func externalHeadlineSaysOneRatingForExactlyOne() {
        let headline = BookRatingSection.externalHeadline(ExternalScore(average: 4.4, count: 1))
        #expect(headline == "\u{2605} 4.4 · 1 rating")
    }

    @Test func externalSentenceSpeaksAverageAndCompactCount() {
        let sentence = BookRatingSection.externalSentence(ExternalScore(average: 4.4, count: 12_000))
        #expect(sentence == "Rated 4.4 out of 5 stars from 12k ratings")
    }

    @Test func externalSentenceSaysOneRatingForExactlyOne() {
        let sentence = BookRatingSection.externalSentence(ExternalScore(average: 4.4, count: 1))
        #expect(sentence == "Rated 4.4 out of 5 stars from 1 rating")
    }

    // MARK: - A score only your listeners gave

    private func snapshot(external: ExternalScore?, canRefresh: Bool = false) -> BookRatingsSnapshot {
        BookRatingsSnapshot(
            listeners: ListenersAverage(averageHalfStars: 10, count: 1),
            mine: nil,
            external: external,
            breakdown: [],
            canRefresh: canRefresh,
            isRefreshingExternal: false
        )
    }

    @Test func aListenersOnlyScoreHasNoHeadline() {
        // Their own line already says it; a second number read off ListenUp's curve would look
        // like a contradiction (a lone 5 stars scores about 4.4).
        let listenersOnly = ExternalScore(average: 4.4, count: 1, outsideShares: [:], listenersShare: 1)
        #expect(listenersOnly.isListenersOnly)
        #expect(BookRatingSection.headline(snapshot(external: listenersOnly)) == nil)
    }

    @Test func aScoreAnOutsideSourceJoinsKeepsItsHeadline() {
        let mixed = ExternalScore(average: 4.3, count: 1_008, outsideShares: [.audible: 0.9], listenersShare: 0.1)
        #expect(!mixed.isListenersOnly)
        #expect(BookRatingSection.headline(snapshot(external: mixed)) == mixed)
    }

    @Test func anAdminSeesRefreshWhereAListenersOnlyHeadlineWouldSit() {
        let listenersOnly = ExternalScore(average: 4.4, count: 1, outsideShares: [:], listenersShare: 1)
        #expect(BookRatingSection.showsRefreshAction(snapshot(external: listenersOnly, canRefresh: true)))
        #expect(!BookRatingSection.showsRefreshAction(snapshot(external: listenersOnly, canRefresh: false)))
    }

    @Test func starsLabelSpeaksTheSharedDefinition() {
        #expect(RatingStarsView.starsLabel(8) == "4")
        #expect(RatingStarsView.starsLabel(7) == "3.5")
        // An average rounds to the nearest half, exactly as Android and web say it.
        #expect(RatingStarsView.starsLabel(7.4) == "3.5")
    }

    @Test func oneListenersRatingIsSpokenAsOneRating() {
        let sentence = BookRatingSection.listenersSentence(ListenersAverage(averageHalfStars: 8, count: 1))
        #expect(sentence == "Your listeners: 4 out of 5 stars, from 1 rating")
    }

    @Test func severalListenersRatingsAreSpokenAsRatings() {
        let sentence = BookRatingSection.listenersSentence(ListenersAverage(averageHalfStars: 7, count: 3))
        #expect(sentence == "Your listeners: 3.5 out of 5 stars, from 3 ratings")
    }

    // MARK: - Refresh before a score exists

    @Test func refreshActionShowsForAnAdminWithNoScoreYet() {
        let snapshot = BookRatingsSnapshot(
            listeners: nil,
            mine: nil,
            external: nil,
            breakdown: [],
            canRefresh: true,
            isRefreshingExternal: false
        )

        #expect(BookRatingSection.showsRefreshAction(snapshot) == true)
    }

    @Test func refreshActionIsHiddenForANonAdmin() {
        let snapshot = BookRatingsSnapshot(
            listeners: nil,
            mine: nil,
            external: nil,
            breakdown: [],
            canRefresh: false,
            isRefreshingExternal: false
        )

        #expect(BookRatingSection.showsRefreshAction(snapshot) == false)
    }

    @Test func refreshActionIsHiddenOnceAScoreExists() {
        let snapshot = BookRatingsSnapshot(
            listeners: nil,
            mine: nil,
            external: ExternalScore(average: 4.4, count: 12_000),
            breakdown: [],
            canRefresh: true,
            isRefreshingExternal: false
        )

        #expect(BookRatingSection.showsRefreshAction(snapshot) == false)
    }

    @Test func refreshActionStaysVisibleAndBusyWhileRefreshing() {
        let snapshot = BookRatingsSnapshot(
            listeners: nil,
            mine: nil,
            external: nil,
            breakdown: [],
            canRefresh: true,
            isRefreshingExternal: true
        )

        // Still shown while a refresh is in flight — it doesn't disappear, it goes busy.
        #expect(BookRatingSection.showsRefreshAction(snapshot) == true)
        #expect(snapshot.isRefreshingExternal == true)
    }

    // MARK: - Breakdown sheet

    @Test func sourceRowShowsNameAverageAndCompactCount() {
        let row = RatingBreakdownSheet.sourceRow(ExternalRatingRow(source: .audible, average: 4.5, count: 8_100))
        #expect(row == "Audible · 4.5 · 8.1k")
    }

    @Test func aSourceRowCarriesItsShareOfTheScore() {
        let row = RatingBreakdownSheet.sourceRow(
            ExternalRatingRow(source: .audible, average: 4.7, count: 1_007),
            share: 0.38
        )
        #expect(row == "Audible · 4.7 · 1k · 38%")
    }

    @Test func combinedFromNamesHowManySourcesWhenThereAreSeveral() {
        let three = ExternalScore(
            average: 4.3,
            count: 101_068,
            outsideShares: [.audible: 0.4, .hardcover: 0.2, .goodreads: 0.4],
            listenersShare: nil
        )
        #expect(RatingBreakdownSheet.combinedFrom(three) == "Combined from 3 sources")
    }

    @Test func combinedFromIsSilentForOneSource() {
        let one = ExternalScore(average: 4.4, count: 1_007, outsideShares: [.audible: 1], listenersShare: nil)
        #expect(RatingBreakdownSheet.combinedFrom(one) == nil)
        #expect(RatingBreakdownSheet.combinedFrom(nil) == nil)
    }

    @Test func yourListenersAreARowWhenTheyArePartOfTheScore() {
        let score = ExternalScore(average: 4.3, count: 1_010, outsideShares: [.audible: 0.8], listenersShare: 0.2)
        let rows = RatingBreakdownSheet.rows(
            breakdown: [ExternalRatingRow(source: .audible, average: 4.7, count: 1_007)],
            score: score,
            listeners: ListenersAverage(averageHalfStars: 8, count: 3)
        )
        #expect(rows == ["Audible · 4.7 · 1k · 80%", "Your listeners · 4.0 · 3 · 20%"])
    }

    @Test func yourListenersAreNoRowWhenTheScoreLeavesThemOut() {
        let score = ExternalScore(average: 4.4, count: 1_007, outsideShares: [.audible: 1], listenersShare: nil)
        let rows = RatingBreakdownSheet.rows(
            breakdown: [ExternalRatingRow(source: .audible, average: 4.7, count: 1_007)],
            score: score,
            listeners: ListenersAverage(averageHalfStars: 8, count: 3)
        )
        #expect(rows == ["Audible · 4.7 · 1k · 100%"])
    }

    @Test func sourceRowSpeaksAGoodreadsRowToo() {
        let row = RatingBreakdownSheet.sourceRow(ExternalRatingRow(source: .goodreads, average: 4.1, count: 620))
        #expect(row == "Goodreads · 4.1 · 620")
    }

    // MARK: - Hit-testing

    @Test func touchesMapToHalfStarsFromTheLeadingEdge() {
        // Five 20pt stars: the left half of a star is half of it, the right half is all of it.
        #expect(RatingStarsView.halfStars(forX: 5, width: 100) == 2)   // clamped up to one star
        #expect(RatingStarsView.halfStars(forX: 25, width: 100) == 3)
        #expect(RatingStarsView.halfStars(forX: 35, width: 100) == 4)
        #expect(RatingStarsView.halfStars(forX: 69, width: 100) == 7)
        #expect(RatingStarsView.halfStars(forX: 100, width: 100) == 10)
    }

    @Test func touchesOutsideTheStarsClampToOneAndFive() {
        #expect(RatingStarsView.halfStars(forX: -40, width: 100) == 2)
        #expect(RatingStarsView.halfStars(forX: 180, width: 100) == 10)
        #expect(RatingStarsView.halfStars(forX: 50, width: 0) == 2)
    }

    @Test func rightToLeftMirrorsTheTouch() {
        // In RTL the first star sits at the right edge.
        #expect(RatingStarsView.halfStars(forX: 95, width: 100, isRightToLeft: true) == 2)
        #expect(RatingStarsView.halfStars(forX: 31, width: 100, isRightToLeft: true) == 7)
        #expect(RatingStarsView.halfStars(forX: 0, width: 100, isRightToLeft: true) == 10)
    }

    @Test func glyphsFillInHalves() {
        let glyphs = (0..<5).map { RatingStarsView.symbol(halfStars: 7, index: $0) }
        #expect(glyphs == ["star.fill", "star.fill", "star.fill", "star.leadinghalf.filled", "star"])
        #expect((0..<5).map { RatingStarsView.symbol(halfStars: 0, index: $0) } == Array(repeating: "star", count: 5))
    }

    // MARK: - VoiceOver stepping

    @Test func theFirstIncrementFromUnratedLandsOnOneStar() {
        #expect(RatingStarsView.stepped(0, by: 1) == 2)
        #expect(RatingStarsView.stepped(0, by: -1) == 0)
    }

    @Test func steppingMovesOneHalfWithinOneToFive() {
        #expect(RatingStarsView.stepped(7, by: 1) == 8)
        #expect(RatingStarsView.stepped(7, by: -1) == 6)
        #expect(RatingStarsView.stepped(10, by: 1) == 10)
        #expect(RatingStarsView.stepped(2, by: -1) == 2)
    }

    @Test func spokenValueSaysNotRatedUntilAStarIsChosen() {
        #expect(RatingStarsView.spokenValue(halfStars: 0) == String(localized: "rating.stars_unrated"))
        #expect(RatingStarsView.spokenValue(halfStars: 7) == String(
            format: String(localized: "rating.stars_a11y"),
            "3.5"
        ))
    }

    // MARK: - Note cap (UTF-16, like Kotlin's String.length)

    @Test func noteCountsUTF16UnitsNotCharacters() {
        // Each emoji is one Character but two UTF-16 units — the server counts the units.
        #expect(RatingNote.length("🎧🎧") == 4)
        #expect(RatingNote.length("abc") == 3)
    }

    @Test func anEditWithinTheCapIsKept() {
        #expect(RatingNote.limited("hello", previous: "hell", maxLength: 280) == "hello")
    }

    @Test func anEmojiThatWouldCrossTheCapIsRefused() {
        let atBoundary = String(repeating: "a", count: 279)
        // 279 + 2 UTF-16 units = 281: grapheme count would say 280 and let it slip past.
        let limited = RatingNote.limited(atBoundary + "🎧", previous: atBoundary, maxLength: 280)

        #expect(limited == atBoundary)
        #expect(RatingNote.length(limited) <= 280)
    }

    @Test func aPasteIsClippedToWhatFitsAndNeverChopsTheEnd() {
        let previous = "Great" + String(repeating: "!", count: 270) + " end."
        #expect(RatingNote.length(previous) == 280)
        // Room for nothing: inserting mid-note is refused, and the tail survives intact.
        let middle = previous.index(previous.startIndex, offsetBy: 5)
        var proposed = previous
        proposed.insert(contentsOf: "🎧🎧", at: middle)
        #expect(RatingNote.limited(proposed, previous: previous, maxLength: 280) == previous)

        // Room for three units: the pasted "🎧🎧" keeps only the emoji that fits whole.
        let shorter = String(previous.dropFirst(3))   // 277
        var pasted = shorter
        pasted.insert(contentsOf: "🎧🎧", at: shorter.startIndex)
        let limited = RatingNote.limited(pasted, previous: shorter, maxLength: 280)
        #expect(limited == "🎧" + shorter)
        #expect(limited.hasSuffix(" end."))
        #expect(RatingNote.length(limited) == 279)
    }

    @Test func deletingFromAnOverlongNoteIsAlwaysAllowed() {
        let overlong = String(repeating: "a", count: 300)
        let shorter = String(overlong.dropLast())
        #expect(RatingNote.limited(shorter, previous: overlong, maxLength: 280) == shorter)
    }

    // MARK: - Reader rows

    @Test func aReaderRowCarriesTheirStarsAndNote() {
        let row = BookReaderRow(from: reader(
            id: "u1",
            name: "Marcus Lee",
            progressPct: 62,
            rating: rating(halfStars: 9, note: "Couldn't stop")
        ))

        #expect(row.halfStars == 9)
        #expect(row.note == "Couldn't stop")
        #expect(row.isRatedOnly == false)
    }

    @Test func aReaderWithoutARatingHasNoStars() {
        let row = BookReaderRow(from: reader(id: "u2", name: "David Warren", finishes: [1_711_929_600_000]))

        #expect(row.halfStars == nil)
        #expect(row.note == nil)
        #expect(row.isRatedOnly == false)
    }

    @Test func aReaderWhoOnlyRatedIsRatedOnly() {
        let rows = BookReadersObserver.rows(from: BookReaders(readers: [
            reader(id: "u3", name: "Priya Shah", rating: rating(halfStars: 6, userId: "u3"))
        ]))

        #expect(rows.count == 1)
        #expect(rows[0].isRatedOnly == true)
        #expect(rows[0].isReading == false)
        #expect(rows[0].lastFinished == nil)
        #expect(rows[0].halfStars == 6)
    }
}
