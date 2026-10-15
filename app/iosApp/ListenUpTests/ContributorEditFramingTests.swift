import Testing
@testable import ListenUp

/// Which frame the contributor edit header shows while a photo or a frame is staged.
@Suite("ContributorEditObserver.displayFrame")
struct ContributorEditFramingTests {
    private let saved = ImageFrameValue(focusX: 0.5, focusY: 0.2, zoom: 1.6)
    private let staged = ImageFrameValue(focusX: 0.3, focusY: 0.6, zoom: 2.4)

    @Test func theSavedPhotoWearsItsSavedFrame() {
        #expect(ContributorEditObserver.displayFrame(staged: nil, saved: saved, hasStagedImage: false) == saved)
    }

    @Test func aStagedFrameWinsOverTheSavedOne() {
        #expect(ContributorEditObserver.displayFrame(staged: staged, saved: saved, hasStagedImage: false) == staged)
        #expect(ContributorEditObserver.displayFrame(staged: staged, saved: saved, hasStagedImage: true) == staged)
    }

    @Test func aNewPhotoNeverWearsTheOldPhotosFrame() {
        #expect(ContributorEditObserver.displayFrame(staged: nil, saved: saved, hasStagedImage: true) == nil)
    }
}
