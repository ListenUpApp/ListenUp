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

    @Test func lastLineCopyFollowsTheState() {
        #expect(LibraryCardState.inProgress(fraction: 0.1, timeLeftMs: 145_860_000).lastLine
                == String(format: String(localized: "book.time_left"), DurationFormatting.hoursMinutes(ms: 145_860_000)))
        #expect(LibraryCardState.finished(durationMs: 43_440_000).lastLine
                == String(format: String(localized: "library.card_finished_length"), DurationFormatting.hoursMinutes(ms: 43_440_000)))
        #expect(LibraryCardState.notStarted(durationMs: 43_440_000).lastLine == DurationFormatting.hoursMinutes(ms: 43_440_000))
    }

    @Test func lastLineReadsInEnglish() {
        #expect(LibraryCardState.inProgress(fraction: 0.1, timeLeftMs: 145_860_000).lastLine == "40h 31m left")
        #expect(LibraryCardState.finished(durationMs: 43_440_000).lastLine == "Finished · 12h 4m")
    }
}
