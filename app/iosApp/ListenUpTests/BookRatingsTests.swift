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
            rating: rating
        )
    }

    // MARK: - Observer mapping

    @Test func loadingMapsToLoading() {
        #expect(BookRatingsObserver.phase(from: BookRatingsUiStateLoading.shared) == .loading)
    }

    @Test func readyWithNobodyRatingHasNoAverageAndNoRatingOfMine() {
        let phase = BookRatingsObserver.phase(from: BookRatingsUiStateReady(listeners: nil, mine: nil))

        #expect(phase == .ready(BookRatingsSnapshot(listeners: nil, mine: nil)))
    }

    @Test func readyCarriesTheAverageAndMyRatingAsNativeValues() {
        let state = BookRatingsUiStateReady(
            listeners: ListenerAverage(averageHalfStars: 7.5, count: 3),
            mine: rating(halfStars: 7, note: "Loved the narrator")
        )

        let phase = BookRatingsObserver.phase(from: state)

        #expect(phase == .ready(BookRatingsSnapshot(
            listeners: ListenersAverage(averageHalfStars: 7.5, count: 3),
            mine: MyRating(halfStars: 7, note: "Loved the narrator")
        )))
    }

    @Test func starsLabelSpeaksTheSharedDefinition() {
        #expect(RatingStarsView.starsLabel(8) == "4")
        #expect(RatingStarsView.starsLabel(7) == "3.5")
        // An average rounds to the nearest half, exactly as Android and web say it.
        #expect(RatingStarsView.starsLabel(7.4) == "3.5")
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
