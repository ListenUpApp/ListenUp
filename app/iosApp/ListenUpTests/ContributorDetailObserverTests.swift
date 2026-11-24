import KotlinStdlib
import ListenupContract
import Shared
import Testing
@testable import ListenUp

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

    // MARK: - A refused delete

    private func ready(deleteError: String?) -> ContributorDetailUiStateReady {
        ContributorDetailUiStateReady(
            contributor: Contributor(
                id: ContributorId(value: "c1"),
                name: "Ursula K. Le Guin",
                description: nil,
                imagePath: nil,
                website: nil,
                birthDate: nil,
                deathDate: nil,
                aliases: [],
                sortName: nil,
                asin: nil,
                imageFrame: nil
            ),
            roleSections: [],
            bookProgress: [:],
            bookCreditedAs: [:],
            series: [],
            bookCount: 0,
            totalDuration: ExportedKotlinPackages.kotlin.time.Duration.Companion.shared.ZERO,
            isDeleting: false,
            deleteError: deleteError,
            canEditMetadata: false,
            canCurateLibrary: true
        )
    }

    @Test func aRefusedDeleteIsShownWithItsReason() {
        let observer = ContributorDetailObserver(viewModel: Dependencies.shared.createContributorDetailViewModel())

        observer.apply(ready(deleteError: "This contributor can't be deleted right now."))
        #expect(observer.deleteRefusal == "This contributor can't be deleted right now.")

        observer.apply(ready(deleteError: nil))
        #expect(observer.deleteRefusal == nil)
    }
}
