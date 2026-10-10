import Testing
@preconcurrency import Shared
@testable import ListenUp

@MainActor
@Suite("Library status models")
struct LibraryStatusModelsTests {
    @Test func inProgressBridgesFractionAndTimeLeft() {
        let state = LibraryCardState.from(BookCardStatusInProgress(fraction: 0.25, timeLeftMs: 145_860_000))
        #expect(state == .inProgress(fraction: 0.25, timeLeftMs: 145_860_000))
    }

    @Test func finishedAndNotStartedBridgeTheLength() {
        #expect(LibraryCardState.from(BookCardStatusFinished(durationMs: 43_440_000)) == .finished(durationMs: 43_440_000))
        #expect(LibraryCardState.from(BookCardStatusNotStarted(durationMs: 60_000)) == .notStarted(durationMs: 60_000))
    }

    @Test func countsReadPerFilter() {
        let counts = LibraryStatusCounts(BookStatusCounts(all: 248, inProgress: 4, notStarted: 183, finished: 61))
        #expect(counts.count(for: .all) == 248)
        #expect(counts.count(for: .inProgress) == 4)
        #expect(counts.count(for: .notStarted) == 183)
        #expect(counts.count(for: .finished) == 61)
    }

    @Test func filtersAreOfferedInBoardOrder() {
        #expect(LibraryStatusOptions.filters == [.all, .inProgress, .notStarted, .finished])
    }
}
