import Testing
@testable import ListenUp

@Suite("SeriesProgressState")
struct SeriesProgressStateTests {
    @Test func allFinishedIsComplete() {
        #expect(SeriesProgressState(finishedCount: 3, totalCount: 3) == .complete)
    }

    @Test func noneFinishedIsNotStarted() {
        #expect(SeriesProgressState(finishedCount: 0, totalCount: 3) == .notStarted)
    }

    @Test func partialReportsFinishedTotalAndFraction() {
        #expect(SeriesProgressState(finishedCount: 1, totalCount: 4) == .partial(finished: 1, total: 4))
    }

    @Test func emptySeriesIsNotStarted() {
        #expect(SeriesProgressState(finishedCount: 0, totalCount: 0) == .notStarted)
    }
}
