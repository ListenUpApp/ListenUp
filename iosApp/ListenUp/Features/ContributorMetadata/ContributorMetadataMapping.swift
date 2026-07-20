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
        switch onEnum(of: loadState) {
        case .idle:
            return SearchMapping(results: [], rawHits: [:], isSearching: false, searchError: nil)
        case .inFlight:
            return SearchMapping(results: [], rawHits: [:], isSearching: true, searchError: nil)
        case .loaded(let loaded):
            let rawHits = Dictionary(loaded.results.map { ($0.asin, $0) }) { first, _ in first }
            let results = loaded.results.map { ContributorHitRow(asin: $0.asin, name: $0.name) }
            return SearchMapping(results: results, rawHits: rawHits, isSearching: false, searchError: nil)
        case .failed(let failed):
            return SearchMapping(results: [], rawHits: [:], isSearching: false, searchError: failed.message)
        case .unknown:
            Log.error("Unexpected ContributorSearchLoadState case")
            return SearchMapping(results: [], rawHits: [:], isSearching: false, searchError: nil)
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
        switch onEnum(of: loadState) {
        case .loading:
            return PreviewMapping(phase: .loading, profile: nil, isApplying: false, applyError: nil)
        case .missing:
            return PreviewMapping(phase: .missing, profile: nil, isApplying: false, applyError: nil)
        case .failed(let failed):
            return PreviewMapping(phase: .failed(failed.message), profile: nil, isApplying: false, applyError: nil)
        case .ready(let ready):
            return PreviewMapping(
                phase: .ready,
                profile: profile(from: ready.profile),
                isApplying: ready.isApplying,
                applyError: ready.applyError
            )
        case .unknown:
            Log.error("Unexpected ContributorPreviewLoadState case")
            return PreviewMapping(phase: .loading, profile: nil, isApplying: false, applyError: nil)
        }
    }

    static func profile(from profile: MetadataContributorProfile) -> ContributorProfilePreview {
        ContributorProfilePreview(
            asin: profile.asin,
            name: profile.name,
            bio: profile.description_,
            imageURL: profile.imageUrl,
            birthDate: profile.birthDate,
            deathDate: profile.deathDate,
            website: profile.website
        )
    }

    // MARK: - Events

    /// Whether the one-shot event is the apply-succeeded outcome. `ContributorMetadataEvent`
    /// currently has a single case, but this stays exhaustive over `onEnum` so a future case
    /// added on the Kotlin side fails loudly here instead of silently flipping `didApply`.
    static func isApplySuccess(_ event: ContributorMetadataEvent) -> Bool {
        switch onEnum(of: event) {
        case .metadataApplied:
            return true
        case .unknown:
            Log.error("Unexpected ContributorMetadataEvent case")
            return false
        }
    }
}
