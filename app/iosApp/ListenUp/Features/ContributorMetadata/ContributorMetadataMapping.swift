import Foundation
import Shared

/// Pure transforms from the Kotlin `ContributorMetadataViewModel` sealed sub-states into the
/// flattened Swift value types `ContributorMetadataObserver` exposes. Kept free of `@Observable`/
/// actor state so every branch is unit-testable in isolation (see
/// `ContributorMetadataMappingTests`) — mirrors `MetadataMatchMapping`.
enum ContributorMetadataMapping {
    // MARK: - Search

    /// The observer-facing projection of `ContributorSearchLoadState`.
    struct SearchMapping {
        let results: [ContributorHitRow]
        let rawHits: [String: MetadataContributorHit]
        let isSearching: Bool
        let searchError: String?
    }

    static func search(from loadState: ContributorSearchLoadState) -> SearchMapping {
        switch loadState.sealedType() {
        case .idle:
            return SearchMapping(results: [], rawHits: [:], isSearching: false, searchError: nil)
        case .inFlight:
            return SearchMapping(results: [], rawHits: [:], isSearching: true, searchError: nil)
        case .loaded(let loadedType):
            let loaded = loadedType.value
            let rawHits = Dictionary(loaded.results.map { ($0.asin, $0) }) { first, _ in first }
            let results = loaded.results.map { ContributorHitRow(asin: $0.asin, name: $0.name) }
            return SearchMapping(results: results, rawHits: rawHits, isSearching: false, searchError: nil)
        case .failed(let failedType):
            let failed = failedType.value
            return SearchMapping(results: [], rawHits: [:], isSearching: false, searchError: failed.message)
        }
    }

    // MARK: - Preview

    /// The observer-facing projection of `ContributorPreviewLoadState`.
    struct PreviewMapping {
        let phase: ContributorPreviewPhase
        let profile: ContributorProfilePreview?
        let isApplying: Bool
        let applyError: String?
    }

    static func preview(from loadState: ContributorPreviewLoadState) -> PreviewMapping {
        switch loadState.sealedType() {
        case .loading:
            return PreviewMapping(phase: .loading, profile: nil, isApplying: false, applyError: nil)
        case .missing:
            return PreviewMapping(phase: .missing, profile: nil, isApplying: false, applyError: nil)
        case .failed(let failedType):
            let failed = failedType.value
            return PreviewMapping(phase: .failed(failed.message), profile: nil, isApplying: false, applyError: nil)
        case .ready(let readyType):
            let ready = readyType.value
            return PreviewMapping(
                phase: .ready,
                profile: profile(from: ready.profile),
                isApplying: ready.isApplying,
                applyError: ready.applyError
            )
        }
    }

    static func profile(from profile: MetadataContributorProfile) -> ContributorProfilePreview {
        ContributorProfilePreview(
            asin: profile.asin,
            name: profile.name,
            bio: profile.descriptionText,
            imageURL: profile.imageUrl,
            birthDate: profile.birthDate,
            deathDate: profile.deathDate,
            website: profile.website
        )
    }

    // MARK: - Events

    /// Whether the one-shot event is the apply-succeeded outcome. `ContributorMetadataEvent`
    /// currently has a single case, but this stays exhaustive over `sealedType()` so a future case
    /// added on the Kotlin side fails loudly here instead of silently flipping `didApply`.
    static func isApplySuccess(_ event: ContributorMetadataEvent) -> Bool {
        switch event.sealedType() {
        case .metadataApplied:
            return true
        }
    }
}
