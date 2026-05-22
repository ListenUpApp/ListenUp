import Testing
@testable import listenup

struct LiveActivityContentMapperTests {

    private func snapshot(
        bookPositionMs: Int64 = 0,
        bookDurationMs: Int64 = 10_000,
        chapterPositionMs: Int64 = 0,
        chapterDurationMs: Int64 = 5_000,
        isPlaying: Bool = true
    ) -> LiveActivitySnapshot {
        LiveActivitySnapshot(
            bookId: "book-1",
            bookTitle: "The Way of Kings",
            authorName: "Brandon Sanderson",
            coverBlurHash: nil,
            coverPath: nil,
            chapterTitle: "Chapter 1",
            isPlaying: isPlaying,
            bookPositionMs: bookPositionMs,
            bookDurationMs: bookDurationMs,
            chapterPositionMs: chapterPositionMs,
            chapterDurationMs: chapterDurationMs
        )
    }

    @Test func attributesCarryStaticIdentity() {
        let attributes = LiveActivityContentMapper.attributes(from: snapshot())
        #expect(attributes.bookId == "book-1")
        #expect(attributes.bookTitle == "The Way of Kings")
        #expect(attributes.authorName == "Brandon Sanderson")
    }

    @Test func contentStateComputesProgressFractions() {
        let state = LiveActivityContentMapper.contentState(
            from: snapshot(bookPositionMs: 5_000, bookDurationMs: 10_000,
                           chapterPositionMs: 2_500, chapterDurationMs: 5_000)
        )
        #expect(state.bookProgress == 0.5)
        #expect(state.chapterProgress == 0.5)
    }

    @Test func progressIsZeroWhenDurationIsZero() {
        let state = LiveActivityContentMapper.contentState(
            from: snapshot(bookDurationMs: 0, chapterDurationMs: 0)
        )
        #expect(state.bookProgress == 0)
        #expect(state.chapterProgress == 0)
    }

    @Test func elapsedAndRemainingAreHumanFormatted() {
        // 2h 14m elapsed of a 6h 46m book → 4h 32m remaining.
        let twoHrs14: Int64 = (2 * 3600 + 14 * 60) * 1000
        let total: Int64 = (6 * 3600 + 46 * 60) * 1000
        let state = LiveActivityContentMapper.contentState(
            from: snapshot(bookPositionMs: twoHrs14, bookDurationMs: total)
        )
        #expect(state.elapsedDescription == "2h 14m")
        #expect(state.remainingDescription == "4h 32m left")
    }

    @Test func subHourDurationsOmitTheHourComponent() {
        let state = LiveActivityContentMapper.contentState(
            from: snapshot(bookPositionMs: 14 * 60 * 1000, bookDurationMs: 60 * 60 * 1000)
        )
        #expect(state.elapsedDescription == "14m")
    }
}
