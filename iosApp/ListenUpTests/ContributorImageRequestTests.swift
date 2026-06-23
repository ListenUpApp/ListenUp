import Testing
@testable import ListenUp

/// Pins the authenticated contributor-photo endpoint shape that `ContributorImageRequest` streams
/// from on the server-fallback branch (mirrors the Compose `/api/v1/contributors/{id}/photo`).
@Suite("ContributorImageRequest")
struct ContributorImageRequestTests {
    @Test func buildsAuthenticatedPhotoEndpoint() {
        let url = ContributorImageRequest.photoURL(base: "https://library.example.com", contributorId: "contrib-42")
        #expect(url?.absoluteString == "https://library.example.com/api/v1/contributors/contrib-42/photo")
    }

    @Test func blankBaseYieldsNil() {
        #expect(ContributorImageRequest.photoURL(base: "", contributorId: "contrib-42") == nil)
    }
}
