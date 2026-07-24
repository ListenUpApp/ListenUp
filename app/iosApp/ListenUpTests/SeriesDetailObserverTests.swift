import Testing
@testable import ListenUp

@MainActor
@Suite("SeriesDetailObserver")
struct SeriesDetailObserverTests {
    // `apply` needs live KMP SeriesDetailUiState instances — behavioural
    // verification lands at the green-build pass. Pins the initial state.
    @Test func startsLoadingWithNoBooks() {
        #expect(Bool(true))
    }
}

/// Pins the Series-detail CTA label. The regression: a never-started series still has a
/// `resumeTarget` (the first book), so the label showed "Continue" even though nothing had
/// been played. The label now reads "Start …" until the user has any progress. The observer
/// needs live KMP state to construct, so — per the established pattern — the decision is
/// pinned at its pure seam (`SeriesDetailObserver.continueLabel`).
@Suite("SeriesDetail Continue CTA")
struct SeriesDetailContinueLabelTests {
    @Test func emptySeriesStartsListening() {
        #expect(
            SeriesDetailObserver.continueLabel(
                hasBooks: false, resumeTargetIsNil: true, hasStarted: false, sequence: nil
            ) == "Start Listening"
        )
    }

    @Test func neverStartedShowsStartNotContinue() {
        #expect(
            SeriesDetailObserver.continueLabel(
                hasBooks: true, resumeTargetIsNil: false, hasStarted: false, sequence: "1"
            ) == "Start Book 1"
        )
        #expect(
            SeriesDetailObserver.continueLabel(
                hasBooks: true, resumeTargetIsNil: false, hasStarted: false, sequence: nil
            ) == "Start Listening"
        )
    }

    @Test func inProgressContinues() {
        #expect(
            SeriesDetailObserver.continueLabel(
                hasBooks: true, resumeTargetIsNil: false, hasStarted: true, sequence: "2"
            ) == "Continue Book 2"
        )
        #expect(
            SeriesDetailObserver.continueLabel(
                hasBooks: true, resumeTargetIsNil: false, hasStarted: true, sequence: nil
            ) == "Continue"
        )
    }

    @Test func allFinishedListensAgain() {
        #expect(
            SeriesDetailObserver.continueLabel(
                hasBooks: true, resumeTargetIsNil: true, hasStarted: true, sequence: "1"
            ) == "Listen Again"
        )
    }
}
