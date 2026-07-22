import Testing
@testable import ListenUp

/// Pins the authenticated contributor-photo endpoint shape that `ContributorImageRequest` streams
/// from on the server-fallback branch (mirrors the Compose `/api/v1/contributors/{id}/photo`).
@Suite("ContributorImageRequest")
struct ContributorImageRequestTests {
    @Test func buildsAuthenticatedPhotoEndpointWithVersion() {
        let url = ContributorImageRequest.photoURL(
            base: "https://library.example.com", contributorId: "contrib-42", imagePath: "contributors/aaa.jpg"
        )
        // Content-addressed: the imagePath rides as `?v=` so the URL changes when the photo does,
        // busting URLSession's URL-keyed cache (not just Nuke's key).
        #expect(url?.absoluteString.hasPrefix(
            "https://library.example.com/api/v1/contributors/contrib-42/photo?v="
        ) == true)
    }

    @Test func nilImagePathYieldsBareEndpoint() {
        let url = ContributorImageRequest.photoURL(
            base: "https://library.example.com", contributorId: "contrib-42", imagePath: nil
        )
        #expect(url?.absoluteString == "https://library.example.com/api/v1/contributors/contrib-42/photo")
    }

    @Test func photoURLChangesWhenImagePathChanges() {
        let before = ContributorImageRequest.photoURL(base: "https://x", contributorId: "c-1", imagePath: "contributors/aaa.jpg")
        let after = ContributorImageRequest.photoURL(base: "https://x", contributorId: "c-1", imagePath: "contributors/bbb.jpg")
        #expect(before != after)
    }

    @Test func blankBaseYieldsNil() {
        #expect(ContributorImageRequest.photoURL(base: "", contributorId: "contrib-42", imagePath: "contributors/aaa.jpg") == nil)
    }

    @Test func cacheKeyFoldsImagePath() {
        #expect(
            ContributorImageRequest.cacheKey(contributorId: "c-1", imagePath: "contributors/aaa.jpg")
                == "c-1:contributors/aaa.jpg"
        )
    }

    @Test func cacheKeyChangesWhenImagePathChanges() {
        let before = ContributorImageRequest.cacheKey(contributorId: "c-1", imagePath: "contributors/aaa.jpg")
        let after = ContributorImageRequest.cacheKey(contributorId: "c-1", imagePath: "contributors/bbb.jpg")
        #expect(before != after)
    }

    @Test func cacheKeyStableWhenImagePathNil() {
        #expect(
            ContributorImageRequest.cacheKey(contributorId: "c-1", imagePath: nil) == "c-1:contributor"
        )
    }
}
