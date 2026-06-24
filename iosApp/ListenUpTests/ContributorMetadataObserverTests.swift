import Testing
@testable import ListenUp

@MainActor
@Suite("ContributorMetadataObserver")
struct ContributorMetadataObserverTests {
    // `apply` maps live KMP `ContributorMetadataUiState` instances (which need the Koin
    // graph + repositories to construct meaningfully); behavioural verification of the
    // search → preview → apply flow lands at the integration pass. These pin the native
    // value-type mapping contracts that the view diffs against.

    @Test func hitRowIdentityIsAsin() {
        let row = ContributorHitRow(asin: "B0ABC", name: "Patrick Rothfuss")
        #expect(row.id == "B0ABC")
    }

    @Test func profilePreviewCarriesBioAndDates() {
        let profile = ContributorProfilePreview(
            asin: "B0ABC",
            name: "Patrick Rothfuss",
            bio: "American author.",
            imageURL: "https://example.com/p.jpg",
            birthDate: "1973-06-06",
            deathDate: nil,
            website: nil
        )
        #expect(profile.bio == "American author.")
        #expect(profile.birthDate == "1973-06-06")
        #expect(profile.deathDate == nil)
    }
}
