import ListenupContract
import Shared
import Testing
@testable import ListenUp

/// The edit sheets' first switch: a subject that could not be loaded is its own phase, carrying the
/// error's message and whether asking again could help — never an empty form that looks editable.
@Suite("EditScreenPhase")
@MainActor
struct EditScreenPhaseTests {
    @Test func aRetryableFailureCanBeRetried() {
        let error = TransportErrorTimeout(correlationId: nil, debugInfo: nil)
        #expect(EditScreenPhase.failed(error) == .loadFailed(message: error.message, canRetry: true))
    }

    @Test func aMissingContributorOffersNoRetry() {
        let error = ContributorErrorNotFound(correlationId: nil, debugInfo: nil)
        #expect(EditScreenPhase.failed(error) == .loadFailed(message: error.message, canRetry: false))
    }

    @Test func bookLoadingAndLoadFailedMap() {
        let error = TransportErrorTimeout(correlationId: nil, debugInfo: nil)
        #expect(BookEditObserver.phase(of: BookEditUiStateLoading.shared) == .loading)
        #expect(
            BookEditObserver.phase(of: BookEditUiStateLoadFailed(bookId: "b1", error: error))
                == .loadFailed(message: error.message, canRetry: true)
        )
    }

    @Test func contributorLoadFailedMaps() {
        let error = ContributorErrorNotFound(correlationId: nil, debugInfo: nil)
        #expect(ContributorEditObserver.phase(of: ContributorEditUiStateLoading.shared) == .loading)
        #expect(
            ContributorEditObserver.phase(of: ContributorEditUiStateLoadFailed(contributorId: "c1", error: error))
                == .loadFailed(message: error.message, canRetry: false)
        )
    }

    @Test func seriesLoadFailedMaps() {
        let error = SeriesErrorNotFound(correlationId: nil, debugInfo: nil)
        #expect(SeriesEditObserver.phase(of: SeriesEditUiStateLoading.shared) == .loading)
        #expect(
            SeriesEditObserver.phase(of: SeriesEditUiStateLoadFailed(seriesId: "s1", error: error))
                == .loadFailed(message: error.message, canRetry: false)
        )
    }
}
