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

/// Which phase the preview pane is in — a native mirror of `ContributorPreviewLoadState`.
enum ContributorPreviewPhase: Equatable {
    case loading
    case ready
    /// Honest miss: the catalog has no profile data for this ASIN in the current region.
    case missing
    case failed(String)
}

/// Observes `ContributorMetadataViewModel` — the "Find on Audible" contributor-scrape flow —
/// flattening its sealed `ContributorMetadataUiState` (Idle/Search/Preview) into `@Observable`
/// Swift properties and native value types. Thin over `FlowBridge`; mirrors
/// `ContributorDetailObserver` / `MetadataMatchObserver`. Sub-state mapping (search results,
/// preview phase, profile) is pure and unit-tested in isolation via `ContributorMetadataMapping`.
///
/// There are no per-field toggles: the server applies asin + biography + photo, never the name.
/// Apply is available whenever the preview is [ContributorPreviewLoadState.Ready] and no apply is
/// in flight — see [canApply]. `didApply` comes from the one-shot `events` flow, never a sticky
/// state flag.
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
    private(set) var currentBio: String?

    // MARK: - Preview

    private(set) var selectedAsin: String?
    private(set) var previewPhase: ContributorPreviewPhase?
    private(set) var profile: ContributorProfilePreview?
    private(set) var isApplying: Bool = false
    private(set) var applyError: String?
    /// Flips to `true` on the one-shot `MetadataApplied` event — drives sheet dismissal.
    private(set) var didApply: Bool = false

    /// Apply is available whenever the preview is Ready and no apply is in flight.
    var canApply: Bool { previewPhase == .ready && !isApplying }

    // MARK: - Dependencies

    private let viewModel: ContributorMetadataViewModel
    private let bridge = FlowBridge()

    /// Raw hits keyed by ASIN, off the diff path, so taps can hand the Kotlin object back.
    private var rawHits: [String: MetadataContributorHit] = [:]

    init(viewModel: ContributorMetadataViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.events) { [weak self] event in
            if ContributorMetadataMapping.isApplySuccess(event) {
                self?.didApply = true
            }
        }
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
        if let hit = rawHits[asin] {
            viewModel.selectCandidate(result: hit)
        } else {
            // No raw hit cached (e.g. a deep link straight into the preview) — fall back to
            // loading by ASIN rather than returning silently, which would strand the preview on
            // a spinner waiting for a fetch that never started.
            viewModel.selectAsin(asin: asin)
        }
    }

    func clearSelection() { viewModel.clearSelection() }

    func apply() { viewModel.apply() }

    // MARK: - State mapping

    private func apply(_ state: ContributorMetadataUiState) {
        region = MetadataRegionOption(state.region)

        switch onEnum(of: state) {
        case .idle:
            results = []
            rawHits = [:]
            previewPhase = nil
            profile = nil

        case .search(let search):
            applyContext(search.context)
            query = search.query

            let mapped = ContributorMetadataMapping.search(from: search.loadState)
            results = mapped.results
            rawHits = mapped.rawHits
            isSearching = mapped.isSearching
            searchError = mapped.searchError

            selectedAsin = nil
            previewPhase = nil
            profile = nil
            isApplying = false
            applyError = nil

        case .preview(let preview):
            applyContext(preview.context)
            query = preview.query
            isSearching = false
            searchError = nil
            selectedAsin = preview.match.asin

            let mapped = ContributorMetadataMapping.preview(from: preview.loadState)
            previewPhase = mapped.phase
            profile = mapped.profile
            isApplying = mapped.isApplying
            applyError = mapped.applyError

        case .unknown:
            Log.error("Unexpected ContributorMetadataUiState case")
        }
    }

    private func applyContext(_ context: ContributorContext) {
        contributorName = context.current?.name ?? ""
        currentImagePath = context.current?.imagePath
        currentBio = context.current?.description_
    }
}
