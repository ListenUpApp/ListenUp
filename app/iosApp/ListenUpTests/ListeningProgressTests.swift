import Testing
@testable import ListenUp

/// A book's progress reaches iOS screens as the shared numbers; the cover card reads its time left from them.
@Suite("ListeningProgress")
struct ListeningProgressTests {
    @Test func coverCardTimeLeftIsTheSharedRemainingTime() {
        let text = BookCoverCard.timeLeftLabel(remainingMs: Int64((40 * 60 + 31) * 60_000))
        #expect(text.contains("40h 31m"))
    }

    @Test func percentAndFractionTravelTogetherUnchanged() {
        // 0.4%: a rounding of the fraction here would print 0.
        let progress = ListeningProgress(fraction: 0.004, percent: 1, remainingMs: 1_000)
        #expect(progress.percent == 1)
        #expect(progress.fraction == 0.004)
    }
}
