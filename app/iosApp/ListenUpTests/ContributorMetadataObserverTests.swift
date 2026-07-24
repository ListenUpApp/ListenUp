import Testing
import Shared
@testable import ListenUp

/// Value-type mapping contracts the view diffs against, plus pure coverage of
/// `ContributorMetadataMapping` — the observer's sub-state → native-value seam. Constructing a
/// live `ContributorMetadataViewModel` needs the Koin graph + repositories; behavioural
/// verification of the full search → preview → apply flow lands at the integration pass. These
/// exercise the mapping directly with hand-built Kotlin DTOs — no observer, no flow.
@MainActor
@Suite("ContributorMetadataObserver")
struct ContributorMetadataObserverTests {
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

    // MARK: - Search mapping

    @Test func searchIdleHasNoResults() {
        let mapped = ContributorMetadataMapping.search(from: ContributorSearchLoadStateIdle.shared)
        #expect(mapped.results.isEmpty)
        #expect(mapped.isSearching == false)
        #expect(mapped.searchError == nil)
    }

    @Test func searchInFlightIsSearchingWithNoResults() {
        let mapped = ContributorMetadataMapping.search(from: ContributorSearchLoadStateInFlight.shared)
        #expect(mapped.isSearching == true)
        #expect(mapped.results.isEmpty)
    }

    @Test func searchLoadedMapsHitsAndRawLookup() {
        let hits = [
            MetadataContributorHit(asin: "B01", name: "Patrick Rothfuss"),
            MetadataContributorHit(asin: "B02", name: "Pat Rothfuss")
        ]
        let mapped = ContributorMetadataMapping.search(from: ContributorSearchLoadStateLoaded(results: hits))
        #expect(mapped.results.map(\.asin) == ["B01", "B02"])
        #expect(mapped.rawHits["B01"]?.name == "Patrick Rothfuss")
        #expect(mapped.isSearching == false)
    }

    @Test func searchFailedCarriesMessage() {
        let mapped = ContributorMetadataMapping.search(
            from: ContributorSearchLoadStateFailed(message: "Search timed out.")
        )
        #expect(mapped.searchError == "Search timed out.")
        #expect(mapped.results.isEmpty)
    }

    // MARK: - Preview mapping

    @Test func previewLoadingHasNoProfile() {
        let mapped = ContributorMetadataMapping.preview(from: ContributorPreviewLoadStateLoading.shared)
        #expect(mapped.phase == .loading)
        #expect(mapped.profile == nil)
    }

    @Test func previewMissingIsNotAnError() {
        let mapped = ContributorMetadataMapping.preview(from: ContributorPreviewLoadStateMissing.shared)
        #expect(mapped.phase == .missing)
        #expect(mapped.profile == nil)
        #expect(mapped.applyError == nil)
    }

    @Test func previewFailedCarriesMessage() {
        let mapped = ContributorMetadataMapping.preview(
            from: ContributorPreviewLoadStateFailed(message: "Something went wrong.")
        )
        #expect(mapped.phase == .failed("Something went wrong."))
    }

    @Test func previewReadyMapsProfileAndApplyState() {
        let profile = MetadataContributorProfile(
            asin: "B0ABC",
            name: "Patrick Rothfuss",
            sortName: "Rothfuss, Patrick",
            description: "American author.",
            imageUrl: "https://example.com/p.jpg",
            birthDate: "1973-06-06",
            deathDate: nil,
            website: nil
        )
        let mapped = ContributorMetadataMapping.preview(
            from: ContributorPreviewLoadStateReady(profile: profile, isApplying: true, applyError: nil)
        )
        #expect(mapped.phase == .ready)
        #expect(mapped.profile?.bio == "American author.")
        #expect(mapped.isApplying == true)
    }

    // MARK: - Events

    @Test func metadataAppliedEventIsApplySuccess() {
        #expect(ContributorMetadataMapping.isApplySuccess(ContributorMetadataEventMetadataApplied.shared) == true)
    }
}
