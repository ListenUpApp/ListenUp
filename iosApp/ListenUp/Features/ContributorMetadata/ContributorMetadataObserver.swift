import Foundation
import Observation
import Shared

/// A single Audible search hit, mapped to a native value type at the observer boundary so it
/// never re-bridges across the Kotlin seam inside a SwiftUI `ForEach` (per `iosApp/CLAUDE.md`
/// rule 8). Carries only the [asin] (stable id + action key) and display [name].
struct ContributorHitRow: Identifiable, Hashable {
    let asin: String
    let name: String

    var id: String { asin }
}

/// The fetched Audible contributor profile, flattened from the Swift Export-bridged
/// `MetadataContributorProfile` into native Swift values. The Kotlin `description` property is
/// exported as `description_` (Swift Export renames it to dodge the `description` clash); we expose
/// it as `bio` to match the rest of the app.
struct ContributorProfilePreview: Equatable {
    let asin: String
    let name: String
    let bio: String?
    let imageURL: String?
    let birthDate: String?
    let deathDate: String?
    let website: String?
}

/// One toggleable preview field, paired with its current/incoming values for a comparison row.
struct ContributorFieldComparison: Identifiable {
    let field: ContributorMetadataField
    let label: String
    let currentValue: String?
    let newValue: String?
    let isSelected: Bool
    /// `false` when Audible returned nothing for this field (toggle disabled).
    let hasNewValue: Bool

    var id: String { "\(field)" }
}

/// Observes `ContributorMetadataViewModel` — the "Find on Audible" contributor-scrape flow —
/// flattening its flat `ContributorMetadataUiState` into `@Observable` Swift properties and native
/// value types. Thin over `FlowBridge`; mirrors `ContributorDetailObserver` / `MetadataMatchObserver`.
///
/// Swift Export-bridged Kotlin objects (`MetadataContributorHit`, `MetadataContributorProfile`) are mapped to
/// native structs in `apply`; the raw hits are kept in a private `rawHits` map off the diff path so the
/// view can hand back an ASIN on tap without exposing a Kotlin type to a `ForEach`.
@Observable
@MainActor
final class ContributorMetadataObserver {
    // MARK: - Search

    private(set) var region = MetadataRegionOption(MetadataLocale.Companion.shared.DEFAULT)
    /// The live query (the view binds an editable copy and pushes via `updateQuery`).
    private(set) var query: String = ""
    private(set) var results: [ContributorHitRow] = []
    private(set) var isSearching: Bool = false
    private(set) var searchError: String?
    /// The contributor we're scraping for — used for the "Searching for …" context line.
    private(set) var contributorName: String = ""
    private(set) var currentImagePath: String?

    // MARK: - Preview

    private(set) var selectedAsin: String?
    private(set) var profile: ContributorProfilePreview?
    private(set) var isLoadingPreview: Bool = false
    private(set) var previewError: String?
    private(set) var fieldComparisons: [ContributorFieldComparison] = []
    private(set) var hasSelectedFields: Bool = false

    // MARK: - Apply

    private(set) var isApplying: Bool = false
    private(set) var applyError: String?
    /// Flips to `true` once the apply succeeds — drives sheet dismissal.
    private(set) var didApply: Bool = false

    // MARK: - Dependencies

    private let viewModel: ContributorMetadataViewModel
    private let bridge = FlowBridge()

    init(viewModel: ContributorMetadataViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    func start(contributorId: String) { viewModel.`init`(contributorId: contributorId) }
    // MARK: - Actions

    func updateQuery(_ text: String) {
        query = text
        viewModel.updateQuery(query: text)
    }

    func search() { viewModel.search() }

    func changeRegion(_ region: MetadataRegionOption) { viewModel.changeRegion(region: region.locale) }

    func selectCandidate(_ asin: String) {
        guard let hit = rawHits[asin] else { return }
        viewModel.selectCandidate(result: hit)
    }

    func clearSelection() { viewModel.clearSelection() }

    func toggleField(_ field: ContributorMetadataField) { viewModel.toggleField(field: field) }

    func apply() { viewModel.apply() }

    // MARK: - Raw hit lookup (off the diff path)

    /// Raw `MetadataContributorHit`s keyed by ASIN. The view only ever passes ASINs back; the Kotlin
    /// objects stay here so they never enter a `ForEach`.
    private var rawHits: [String: MetadataContributorHit] = [:]

    // MARK: - State mapping

    private func apply(_ state: ContributorMetadataUiState) {
        region = MetadataRegionOption(state.selectedRegion)
        query = state.searchQuery
        contributorName = state.currentContributor?.name ?? ""
        currentImagePath = state.currentContributor?.imagePath

        rawHits = Dictionary(state.searchResults.map { ($0.asin, $0) }) { first, _ in first }
        results = state.searchResults.map { ContributorHitRow(asin: $0.asin, name: $0.name) }
        isSearching = state.isSearching
        searchError = state.searchError

        selectedAsin = state.selectedCandidate?.asin
        isLoadingPreview = state.isLoadingPreview
        previewError = state.previewError
        profile = state.previewProfile.map(Self.mapProfile)
        fieldComparisons = Self.comparisons(state: state)
        hasSelectedFields = viewModel.hasSelectedFields()

        isApplying = state.isApplying
        applyError = state.applyError
        if state.applySuccess { didApply = true }
    }

    private static func mapProfile(_ profile: MetadataContributorProfile) -> ContributorProfilePreview {
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

    /// Builds the per-field comparison rows from the current contributor and the fetched profile.
    /// Image is only offered when Audible returned one (matches the Android `availableFieldCount` logic).
    private static func comparisons(state: ContributorMetadataUiState) -> [ContributorFieldComparison] {
        guard let contributor = state.currentContributor, let profile = state.previewProfile else { return [] }
        let selections = state.selections
        let hasImage = !(profile.imageUrl?.isEmpty ?? true)

        var rows: [ContributorFieldComparison] = [
            ContributorFieldComparison(
                field: .name,
                label: String(localized: "common.name"),
                currentValue: contributor.name,
                newValue: profile.name,
                isSelected: selections.name,
                hasNewValue: !profile.name.isEmpty
            ),
            ContributorFieldComparison(
                field: .biography,
                label: String(localized: "contributor.biography"),
                currentValue: contributor.description_,
                newValue: profile.description_,
                isSelected: selections.biography,
                hasNewValue: !(profile.description_?.isEmpty ?? true)
            )
        ]
        if hasImage {
            rows.append(ContributorFieldComparison(
                field: .image,
                label: String(localized: "common.image"),
                currentValue: nil,
                newValue: profile.imageUrl,
                isSelected: selections.image,
                hasNewValue: true
            ))
        }
        return rows
    }
}
