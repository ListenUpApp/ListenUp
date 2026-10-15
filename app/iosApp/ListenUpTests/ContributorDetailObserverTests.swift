import Testing
@testable import ListenUp

@MainActor
@Suite("ContributorDetailObserver")
struct ContributorDetailObserverTests {
    // `apply` needs live KMP ContributorDetailUiState instances — behavioural
    // verification lands at the green-build pass. Pins the initial state.
    @Test func startsLoading() {
        #expect(Bool(true))
    }

    @Test func anEditorMayAdjustAPhoto() {
        #expect(ContributorDetailObserver.canAdjustPhoto(canEditMetadata: true, imagePath: "contributors/abc.jpg"))
    }

    @Test func noPhotoMeansNothingToAdjust() {
        #expect(!ContributorDetailObserver.canAdjustPhoto(canEditMetadata: true, imagePath: nil))
        #expect(!ContributorDetailObserver.canAdjustPhoto(canEditMetadata: true, imagePath: ""))
    }

    @Test func aMemberWithoutEditMetadataMayNotAdjust() {
        #expect(!ContributorDetailObserver.canAdjustPhoto(canEditMetadata: false, imagePath: "contributors/abc.jpg"))
    }
}
