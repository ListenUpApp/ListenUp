import Foundation
import Observation
import Shared

/// Observes `MetadataViewModel` — the "Match metadata on Audible" wizard — flattening its
/// three-phase sealed `MetadataUiState` (Idle / Search / Preview) and one-shot `MetadataEvent`
/// stream into SwiftUI-native phases the views switch over. Thin over `FlowBridge`; all mapping
/// is pure and unit-tested via `MetadataMatchMapping`.
///
/// The observer owns native error state (`lastError`) rather than the Compose `errorBus`, per the
/// iOS error rule. `appliedToken` / `chapterAppliedToken` are monotonic counters bumped on the
/// corresponding `MetadataEvent` so the view drives the success screen / closes the chapter sheet
/// without polling. `initForBook` is invoked on construction with the book context.
@Observable
@MainActor
final class MetadataMatchObserver {
    // MARK: - Phase

    /// The wizard's top-level phase, flattened for a SwiftUI `switch`.
    enum Phase: Equatable {
        /// Pre-`initForBook`, or the brief gap before the first search lands.
        case idle
        case search(MetadataSearchStatus)
        case preview(MetadataPreviewStatus)
    }

    private(set) var phase: Phase = .idle

    /// The currently selected metadata-lookup region (persists across phases).
    private(set) var region = MetadataRegionOption(MetadataLocale.Companion.shared.DEFAULT)
    /// The live query string (the view binds an editable copy and pushes via `updateQuery`).
    private(set) var query: String = ""

    /// Native error surface; rendered as an alert / inline banner.
    private(set) var lastError: String?

    /// Bumped when `MetadataEvent.MatchApplied` lands — drives the confirmation screen.
    private(set) var appliedToken: Int = 0
    /// Bumped when `MetadataEvent.ChapterNamesApplied` lands — closes the chapter sheet.
    private(set) var chapterAppliedToken: Int = 0

    // MARK: - Dependencies

    private let viewModel: MetadataViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(
        viewModel: MetadataViewModel,
        bookId: String,
        title: String,
        author: String,
        asin: String?
    ) {
        self.viewModel = viewModel
        viewModel.initForBook(bookId: bookId, title: title, author: author, asin: asin)
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.events) { [weak self] in self?.applyEvent($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func updateQuery(_ text: String) {
        query = text
        viewModel.updateQuery(query: text)
    }

    func search() { viewModel.search() }

    func changeRegion(_ region: MetadataRegionOption) { viewModel.changeRegion(region: region.locale) }

    func selectMatch(_ asin: String) {
        guard let book = currentSearchResult(asin: asin) else { return }
        viewModel.selectMatch(result: book)
    }

    func clearSelection() { viewModel.clearSelection() }

    func toggleField(_ field: MetadataField) { viewModel.toggleField(field: field) }
    func toggleAuthor(_ asin: String) { viewModel.toggleAuthor(asin: asin) }
    func toggleNarrator(_ asin: String) { viewModel.toggleNarrator(asin: asin) }
    func toggleSeries(_ asin: String) { viewModel.toggleSeries(asin: asin) }
    func toggleGenre(_ genre: String) { viewModel.toggleGenre(genre: genre) }
    func toggleMood(_ mood: String) { viewModel.toggleMood(mood: mood) }
    func toggleTag(_ tag: String) { viewModel.toggleTag(tag: tag) }
    func selectCover(_ url: String?) { viewModel.selectCover(coverUrl: url) }

    func applyMatch() { viewModel.applyMatch() }

    func toggleChapter(_ ordinal: Int) { viewModel.toggleChapter(ordinal: Int32(ordinal)) }
    func applyChapterNames() { viewModel.applyChapterNames() }

    func dismissError() { lastError = nil }

    // MARK: - Raw match lookup

    /// The raw `MetadataBook` for an ASIN within the current search results, needed to call
    /// `selectMatch`. Held only transiently; the view passes ASINs, not Kotlin types.
    private var rawResults: [String: MetadataBook] = [:]

    private func currentSearchResult(asin: String) -> MetadataBook? { rawResults[asin] }

    // MARK: - State mapping

    private func apply(_ state: MetadataUiState) {
        region = MetadataRegionOption(state.region)
        switch onEnum(of: state) {
        case .idle:
            phase = .idle
            rawResults = [:]
        case .search(let searchState):
            query = searchState.query
            rawResults = Dictionary(searchState.searchResultsForMapping.map { ($0.asin, $0) }) { first, _ in first }
            phase = .search(MetadataMatchMapping.searchPhase(from: searchState))
        case .preview(let previewState):
            query = previewState.query
            rawResults = Dictionary(previewState.searchResults.map { ($0.asin, $0) }) { first, _ in first }
            phase = .preview(MetadataMatchMapping.previewPhase(from: previewState))
        case .unknown:
            Log.error("Unexpected MetadataUiState case")
            phase = .idle
            rawResults = [:]
        }
    }

    private func applyEvent(_ event: MetadataEvent) {
        switch onEnum(of: event) {
        case .matchApplied:
            appliedToken += 1
        case .chapterNamesApplied:
            chapterAppliedToken += 1
        case .unknown:
            Log.error("Unexpected MetadataEvent case")
        }
    }
}

// MARK: - Search-result extraction helper

private extension MetadataUiStateSearch {
    /// The loaded search results, or empty when not yet `Loaded`. Lets the observer keep a raw
    /// ASIN → `MetadataBook` map without re-deriving the sealed `loadState` in two places.
    var searchResultsForMapping: [MetadataBook] {
        (loadState as? SearchLoadStateLoaded)?.results ?? []
    }
}
