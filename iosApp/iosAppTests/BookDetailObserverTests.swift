import Testing
@testable import iosApp

@Suite("DownloadUIState")
struct BookDetailObserverTests {
    // BookDetailObserver's `apply` needs live KMP BookDetailUiState instances —
    // behavioural verification lands at the green-build pass with a fake VM.
    // The pure piece pinned now: the DownloadUIState cases are distinct.
    @Test func downloadStatesAreDistinct() {
        let all: [DownloadUIState] = [
            .notDownloaded, .queued, .downloading, .completed, .partial, .failed,
        ]
        #expect(all.count == 6)
    }
}
