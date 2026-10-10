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

    private func rating(halfStars: Int, note: String? = nil, userId: String = "u1", fromHardcover: Bool = false) -> ListenerRating {
        ListenerRating(
            bookId: "b1",
            userId: userId,
            halfStars: Int32(halfStars),
            note: note,
            ratedAtMs: 1_711_929_600_000,
            fromHardcover: fromHardcover
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
        ExternalRating(source: source, average: average, count: Int32(count), fetchedAtMs: nil)
    }

    @Test func readyWithNobodyRatingHasNoAverageAndNoRatingOfMine() {
        let phase = BookRatingsObserver.phase(from: BookRatingsUiStateReady(
            listeners: nil,
            mine: nil,
            external: nil,
            breakdown: [],
            canRefresh: false,
            isRefreshingExternal: false,
            isCheckingExternal: false
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

    @Test func readyCarriesThatMyRatingCameFromHardcover() {
        let phase = BookRatingsObserver.phase(from: BookRatingsUiStateReady(
            listeners: nil,
            mine: rating(halfStars: 9, fromHardcover: true),
            external: nil,
            breakdown: [],
            canRefresh: false,
            isRefreshingExternal: false,
            isCheckingExternal: false
        ))

        guard case .ready(let snapshot) = phase else { Issue.record("expected ready"); return }
        #expect(snapshot.mine == MyRating(halfStars: 9, note: nil, fromHardcover: true))
    }

    @Test func readyCarriesTheAverageAndMyRatingAsNativeValues() {
        let state = BookRatingsUiStateReady(
            listeners: ListenerAverage(averageHalfStars: 7.5, count: 3),
            mine: rating(halfStars: 7, note: "Loved the narrator"),
            external: nil,
            breakdown: [],
            canRefresh: false,
            isRefreshingExternal: false,
            isCheckingExternal: false
        )

        let phase = BookRatingsObserver.phase(from: state)

        #expect(phase == .ready(BookRatingsSnapshot(
            listeners: ListenersAverage(averageHalfStars: 7.5, count: 3),
            mine: MyRating(halfStars: 7, note: "Loved the narrator", fromHardcover: false),
            external: nil,
            breakdown: [],
            canRefresh: false,
            isRefreshingExternal: false,
            // Only your listeners rated it: their own row says so, and no score row is drawn.
            scoreRow: .absent
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
            isRefreshingExternal: true,
            isCheckingExternal: false
        )

        let phase = BookRatingsObserver.phase(from: state)

        let score = ExternalScore(
            average: 4.4,
            count: 12_000,
            outsideShares: [.audible: 0.6, .goodreads: 0.4],
            listenersShare: nil
        )
        #expect(phase == .ready(BookRatingsSnapshot(
            listeners: nil,
            mine: nil,
            external: score,
            breakdown: [
                ExternalRatingRow(source: .audible, average: 4.5, count: 8_100),
                ExternalRatingRow(source: .goodreads, average: 4.1, count: 3_900)
            ],
            canRefresh: true,
            isRefreshingExternal: true,
            scoreRow: .shown(score),
            outsideSourcesInScore: [.audible, .goodreads]
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
            isRefreshingExternal: false,
            isCheckingExternal: false
        )

        guard case .ready(let snapshot) = BookRatingsObserver.phase(from: state) else {
            Issue.record("expected a ready phase")
            return
        }
        #expect(snapshot.external?.outsideShares == [.audible: 0.8])
        #expect(snapshot.external?.listenersShare == 0.2)
        #expect(snapshot.external?.sourceCount == 2)
        #expect(snapshot.outsideSourcesInScore == [.audible])
        #expect(snapshot.listenersInScore)
    }

    // MARK: - Stars

    @Test func starsLabelSpeaksTheSharedDefinition() {
        #expect(RatingStarsView.starsLabel(8) == "4")
        #expect(RatingStarsView.starsLabel(7) == "3.5")
        // An average rounds to the nearest half, exactly as Android and web say it.
        #expect(RatingStarsView.starsLabel(7.4) == "3.5")
    }

    // MARK: - Breakdown sheet

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

    // MARK: - Yours first

    private let day: Int64 = 86_400_000

    private func snapshot(
        mine: MyRating? = nil,
        listeners: ListenersAverage? = nil,
        external: ExternalScore? = nil,
        breakdown: [ExternalRatingRow] = [],
        canRefresh: Bool = false,
        scoreRow: ScoreRowModel = .noRatings,
        outsideSourcesInScore: [ExternalRatingSource] = [],
        listenersInScore: Bool = false
    ) -> BookRatingsSnapshot {
        BookRatingsSnapshot(
            listeners: listeners,
            mine: mine,
            external: external,
            breakdown: breakdown,
            canRefresh: canRefresh,
            isRefreshingExternal: false,
            isCheckingExternal: scoreRow == .checking,
            scoreRow: scoreRow,
            showsInlineRefresh: canRefresh && (scoreRow == .noRatings || scoreRow == .absent),
            outsideSourcesInScore: outsideSourcesInScore,
            listenersInScore: listenersInScore
        )
    }

    @Test func readyCarriesTheScoreRowTheSharedStateDecided() {
        let checking = BookRatingsObserver.phase(from: BookRatingsUiStateReady(
            listeners: nil, mine: nil, external: nil, breakdown: [],
            canRefresh: true, isRefreshingExternal: false, isCheckingExternal: true
        ))
        guard case .ready(let snap) = checking else { Issue.record("expected ready"); return }
        #expect(snap.scoreRow == .checking)
        #expect(snap.isCheckingExternal)
        #expect(!snap.showsInlineRefresh)

        let nothing = BookRatingsObserver.phase(from: BookRatingsUiStateReady(
            listeners: nil, mine: nil, external: nil, breakdown: [],
            canRefresh: true, isRefreshingExternal: false, isCheckingExternal: false
        ))
        guard case .ready(let none) = nothing else { Issue.record("expected ready"); return }
        #expect(none.scoreRow == .noRatings)
        #expect(none.showsInlineRefresh)
    }

    @Test func readyCarriesEachSourcesFetchTime() {
        let state = BookRatingsUiStateReady(
            listeners: nil, mine: nil, external: nil,
            breakdown: [ExternalRating(source: .hardcover, average: 4.1, count: 88, fetchedAtMs: 1_790_000_000_000)],
            canRefresh: false, isRefreshingExternal: false, isCheckingExternal: false
        )
        guard case .ready(let snap) = BookRatingsObserver.phase(from: state) else {
            Issue.record("expected ready")
            return
        }
        #expect(snap.breakdown.first?.fetchedAtMs == 1_790_000_000_000)
    }

    @Test func theScoreRowNamesItsCountAndSources() {
        let score = ExternalScore(average: 4.6, count: 12_203)
        let snap = snapshot(
            external: score,
            scoreRow: .shown(score),
            outsideSourcesInScore: [.audible, .hardcover],
            listenersInScore: true
        )
        #expect(BookRatingSection.scoreDetail(score, snap) == "12k ratings · Audible, Hardcover, your listeners")
        #expect(BookRatingSection.scoreSentence(score, snap)
            == "ListenUp score: Rated 4.6 out of 5 stars from 12k ratings. Audible, Hardcover, your listeners.")
    }

    @Test func oneRatingIsSaidOnce() {
        #expect(BookRatingSection.countLabel(1) == "1 rating")
        #expect(BookRatingSection.countLabel(3) == "3 ratings")
    }

    @Test func yourListenersReadToOneDecimal() {
        let listeners = ListenersAverage(averageHalfStars: 8, count: 3)
        #expect(listeners.label == "4.0")
        #expect(BookRatingSection.listenersSentence(listeners) == "Your listeners: 4.0 out of 5 stars, from 3 ratings")
        #expect(BookRatingSection.listenersSentence(ListenersAverage(averageHalfStars: 9, count: 1))
            == "Your listeners: 4.5 out of 5 stars, from 1 rating")
    }

    @Test func removingARatingAsksFirstOnlyWhenANoteWouldBeLost() {
        #expect(BookRatingSection.removeNeedsConfirmation(MyRating(halfStars: 8, note: "Loved it", fromHardcover: false)))
        #expect(!BookRatingSection.removeNeedsConfirmation(MyRating(halfStars: 8, note: nil, fromHardcover: false)))
        #expect(!BookRatingSection.removeNeedsConfirmation(MyRating(halfStars: 8, note: "", fromHardcover: false)))
    }

    @Test func theNoteActionSaysAddOrEdit() {
        #expect(BookRatingSection.noteActionTitle(MyRating(halfStars: 8, note: nil, fromHardcover: false)) == "Add a Note")
        #expect(BookRatingSection.noteActionTitle(MyRating(halfStars: 8, note: "Loved it", fromHardcover: false)) == "Edit Note")
    }

    @Test func theValueSaysNotRatedUntilAStarIsChosen() {
        #expect(BookRatingSection.valueLabel(0) == "Not rated")
        #expect(BookRatingSection.valueLabel(9) == "4.5")
    }

    @Test func theSectionSplitsAt560Points() {
        #expect(!BookRatingSection.isSplit(width: 559))
        #expect(BookRatingSection.isSplit(width: 560))
    }

    @Test func freshnessIsSaidInDays() {
        let now: Int64 = 1_000 * day
        #expect(RatingBreakdownSheet.updatedLabel(fetchedAtMs: now - 5, nowMs: now) == "Updated today")
        #expect(RatingBreakdownSheet.updatedLabel(fetchedAtMs: now - day, nowMs: now) == "Updated yesterday")
        #expect(RatingBreakdownSheet.updatedLabel(fetchedAtMs: now - 3 * day, nowMs: now) == "Updated 3 days ago")
        #expect(RatingBreakdownSheet.updatedLabel(fetchedAtMs: nil, nowMs: now) == nil)
    }

    @Test func eachSourceRowCarriesItsShareAndFreshness() {
        let now: Int64 = 1_000 * day
        let score = ExternalScore(
            average: 4.6, count: 12_203,
            outsideShares: [.audible: 0.62], listenersShare: 0.08
        )
        let rows = RatingBreakdownSheet.rows(
            breakdown: [ExternalRatingRow(source: .audible, average: 4.8, count: 11_000, fetchedAtMs: now - 3 * day)],
            score: score,
            listeners: ListenersAverage(averageHalfStars: 8, count: 3),
            nowMs: now
        )
        #expect(rows == [
            SourceRowModel(label: "Audible", average: "4.8", count: "11k ratings", share: 0.62, shareLabel: "62%",
                           updated: "Updated 3 days ago"),
            SourceRowModel(label: "Your listeners", average: "4.0", count: "3 ratings", share: 0.08, shareLabel: "8%",
                           updated: nil)
        ])
    }

    @Test func removingIsAnnouncedToVoiceOver() {
        #expect(BookRatingsObserver.announcement(for: BookRatingsEventRatingRemoved.shared) == "Rating removed")
    }

    @Test func bookDetailPutsTheSocialSectionsAboveChapters() {
        #expect(BookDetailContentSection.allCases == [.social, .chapters, .documents, .details])
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

    @Test func aVerticalSwipeOverTheStarsIsAScrollNotARating() {
        // Saving on lift must not rate a book for someone scrolling Book Detail past its stars.
        #expect(RatingStarsView.isScrollAttempt(CGSize(width: 2, height: 40)))
        #expect(RatingStarsView.isScrollAttempt(CGSize(width: -10, height: -30)))
        #expect(!RatingStarsView.isScrollAttempt(CGSize(width: 60, height: 20)))
        #expect(!RatingStarsView.isScrollAttempt(CGSize(width: 0, height: 5)))   // a tap's wobble
        #expect(!RatingStarsView.isScrollAttempt(.zero))
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
