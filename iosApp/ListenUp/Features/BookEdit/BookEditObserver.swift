import SwiftUI
import Shared

/// Observes `BookEditViewModel`, flattening `BookEditUiState` into `@Observable`
/// properties and dispatching edits as `BookEditUiEvent`s. `NavigateBack` flips
/// `didFinish` so the sheet dismisses.
///
/// The relational lists (contributors, series, genres, tags, moods) are mapped to native
/// `EditableRelation` chips and `RelationSearchResult` rows at the observer boundary — no
/// Swift Export-bridged Kotlin object ever reaches a `ForEach`. Both display+remove and the
/// search-and-add pickers are wired here.
@Observable
@MainActor
final class BookEditObserver {
    private(set) var isLoading: Bool = true

    // Text fields
    private(set) var title: String = ""
    private(set) var sortTitle: String = ""
    private(set) var subtitle: String = ""
    /// The book description — stored as `bookDescription` because Swift Export renames the Kotlin
    /// `description` property to `description_` (dodging the Swift `description` clash).
    private(set) var bookDescription: String = ""
    private(set) var publisher: String = ""
    private(set) var publishYear: String = ""

    // Cover
    private(set) var displayCoverPath: String?
    private(set) var coverHash: String?
    private(set) var isUploadingCover: Bool = false

    // Relations — native projections fed to the views. No bridged Kotlin object ever reaches a
    // ForEach (it would re-bridge on every diff); the mapping happens once in `apply`.
    private(set) var authors: [EditableRelation] = []
    private(set) var narrators: [EditableRelation] = []
    private(set) var series: [EditableRelation] = []
    private(set) var genres: [EditableRelation] = []
    private(set) var tags: [EditableRelation] = []
    private(set) var moods: [EditableRelation] = []
    private(set) var collections: [EditableRelation] = []

    /// Whether the current user is an admin — gates the Collections section (admin-only, per the
    /// shared `BookEditUiState.isAdmin`).
    private(set) var isAdmin: Bool = false

    // Raw Kotlin lists retained for the id→object lookup when a chip's remove button is tapped.
    // Held off the SwiftUI diff path (never iterated by a ForEach), so they don't re-bridge.
    private var rawAuthors: [EditableContributor] = []
    private var rawNarrators: [EditableContributor] = []
    private var rawSeries: [EditableSeries] = []
    private var rawGenres: [EditableGenre] = []
    private var rawTags: [EditableTag] = []
    private var rawMoods: [EditableMood] = []
    private var rawCollections: [EditableCollection] = []

    // Add-picker search sub-state — native projections fed to the result rows. Queries are echoed
    // from shared state so the field stays the single source of truth across re-emits.
    private(set) var authorQuery: String = ""
    private(set) var narratorQuery: String = ""
    private(set) var seriesQuery: String = ""
    private(set) var genreQuery: String = ""
    private(set) var tagQuery: String = ""
    private(set) var moodQuery: String = ""
    private(set) var collectionQuery: String = ""

    private(set) var authorResults: [RelationSearchResult] = []
    private(set) var narratorResults: [RelationSearchResult] = []
    private(set) var seriesResults: [RelationSearchResult] = []
    private(set) var genreResults: [RelationSearchResult] = []
    private(set) var tagResults: [RelationSearchResult] = []
    private(set) var moodResults: [RelationSearchResult] = []
    private(set) var collectionResults: [RelationSearchResult] = []

    private(set) var authorSearching: Bool = false
    private(set) var narratorSearching: Bool = false
    private(set) var seriesSearching: Bool = false
    private(set) var tagSearching: Bool = false
    private(set) var moodSearching: Bool = false

    // Raw search results retained for the id→object lookup when a result row is tapped, off the
    // diff path (never iterated by a ForEach) so they don't re-bridge.
    private var rawSeriesResults: [SeriesSearchResult] = []
    private var rawAuthorResults: [ContributorSearchResult] = []
    private var rawNarratorResults: [ContributorSearchResult] = []
    private var rawGenreResults: [EditableGenre] = []
    private var rawTagResults: [EditableTag] = []
    private var rawMoodResults: [EditableMood] = []
    private var rawCollectionResults: [EditableCollection] = []

    private(set) var hasChanges: Bool = false
    private(set) var isSaving: Bool = false
    private(set) var error: String?
    private(set) var didFinish: Bool = false

    private let viewModel: BookEditViewModel
    private let bridge = FlowBridge()

    init(viewModel: BookEditViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.navActions) { [weak self] in self?.applyNav($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    func loadBook(bookId: String) {
        viewModel.loadBook(bookId: bookId)
        // Ensure the NARRATOR search flow exists even when the book has no narrators yet. The
        // shared VM only sets up per-role search for roles visible at load (AUTHOR always, plus
        // any role with existing contributors), so without this a narrator add-field would be
        // inert. `AddRoleSection` is idempotent (guarded by `containsKey` in the delegate).
        viewModel.onEvent(event: BookEditUiEventAddRoleSection(role: .narrator))
    }

    // MARK: - Field intents

    func setTitle(_ value: String) { viewModel.onEvent(event: BookEditUiEventTitleChanged(title: value)) }
    func setSortTitle(_ value: String) { viewModel.onEvent(event: BookEditUiEventSortTitleChanged(sortTitle: value)) }
    func setSubtitle(_ value: String) { viewModel.onEvent(event: BookEditUiEventSubtitleChanged(subtitle: value)) }
    func setDescription(_ value: String) {
        viewModel.onEvent(event: BookEditUiEventDescriptionChanged(description: value))
    }
    func setPublisher(_ value: String) { viewModel.onEvent(event: BookEditUiEventPublisherChanged(publisher: value)) }
    func setPublishYear(_ value: String) { viewModel.onEvent(event: BookEditUiEventPublishYearChanged(year: value)) }

    // MARK: - Cover intents

    func onCoverPicked(_ data: Data) {
        viewModel.onEvent(event: BookEditUiEventUploadCover(
            imageData: data.toKotlinByteArray(),
            filename: "cover.jpg"
        ))
    }

    // MARK: - Relation remove intents

    func removeContributor(_ relation: EditableRelation, role: ContributorRole) {
        let raw = role == .author ? rawAuthors : rawNarrators
        guard let contributor = raw.first(where: { $0.name == relation.id }) else { return }
        viewModel.onEvent(event: BookEditUiEventRemoveContributor(contributor: contributor, role: role))
    }
    func removeSeries(_ relation: EditableRelation) {
        guard let value = rawSeries.first(where: { $0.name == relation.id }) else { return }
        viewModel.onEvent(event: BookEditUiEventRemoveSeries(series: value))
    }
    func removeGenre(_ relation: EditableRelation) {
        guard let value = rawGenres.first(where: { $0.id == relation.id }) else { return }
        viewModel.onEvent(event: BookEditUiEventRemoveGenre(genre: value))
    }
    func removeTag(_ relation: EditableRelation) {
        guard let value = rawTags.first(where: { $0.id == relation.id }) else { return }
        viewModel.onEvent(event: BookEditUiEventRemoveTag(tag: value))
    }
    func removeMood(_ relation: EditableRelation) {
        guard let value = rawMoods.first(where: { $0.id == relation.id }) else { return }
        viewModel.onEvent(event: BookEditUiEventRemoveMood(mood: value))
    }
    func removeCollection(_ relation: EditableRelation) {
        guard let value = rawCollections.first(where: { $0.id == relation.id }) else { return }
        viewModel.onEvent(event: BookEditUiEventRemoveCollection(collection: value))
    }

    // MARK: - Relation add intents

    // Contributors (per role) — search, select an existing result, or enter a new name.
    func setContributorQuery(_ value: String, role: ContributorRole) {
        viewModel.onEvent(event: BookEditUiEventRoleSearchQueryChanged(role: role, query: value))
    }
    func selectContributorResult(_ result: RelationSearchResult, role: ContributorRole) {
        let raw = role == .author ? rawAuthorResults : rawNarratorResults
        guard let match = raw.first(where: { $0.id == result.id }) else { return }
        viewModel.onEvent(event: BookEditUiEventRoleContributorSelected(role: role, result: match))
    }
    func enterContributor(_ name: String, role: ContributorRole) {
        viewModel.onEvent(event: BookEditUiEventRoleContributorEntered(role: role, name: name))
    }

    // Series — search, select an existing result, or enter a new name.
    func setSeriesQuery(_ value: String) {
        viewModel.onEvent(event: BookEditUiEventSeriesSearchQueryChanged(query: value))
    }
    func selectSeriesResult(_ result: RelationSearchResult) {
        guard let match = rawSeriesResults.first(where: { $0.id == result.id }) else { return }
        viewModel.onEvent(event: BookEditUiEventSeriesSelected(result: match))
    }
    func enterSeries(_ name: String) {
        viewModel.onEvent(event: BookEditUiEventSeriesEntered(name: name))
    }

    // Genres — select an existing genre only (no free-text creation).
    func setGenreQuery(_ value: String) {
        viewModel.onEvent(event: BookEditUiEventGenreSearchQueryChanged(query: value))
    }
    func selectGenreResult(_ result: RelationSearchResult) {
        guard let match = rawGenreResults.first(where: { $0.id == result.id }) else { return }
        viewModel.onEvent(event: BookEditUiEventGenreSelected(genre: match))
    }

    // Tags — search, select an existing tag, or create a new one.
    func setTagQuery(_ value: String) {
        viewModel.onEvent(event: BookEditUiEventTagSearchQueryChanged(query: value))
    }
    func selectTagResult(_ result: RelationSearchResult) {
        guard let match = rawTagResults.first(where: { $0.id == result.id }) else { return }
        viewModel.onEvent(event: BookEditUiEventTagSelected(tag: match))
    }
    func enterTag(_ name: String) {
        viewModel.onEvent(event: BookEditUiEventTagEntered(name: name))
    }

    // Moods — search, select an existing mood, or create a new one.
    func setMoodQuery(_ value: String) {
        viewModel.onEvent(event: BookEditUiEventMoodSearchQueryChanged(query: value))
    }
    func selectMoodResult(_ result: RelationSearchResult) {
        guard let match = rawMoodResults.first(where: { $0.id == result.id }) else { return }
        viewModel.onEvent(event: BookEditUiEventMoodSelected(mood: match))
    }
    func enterMood(_ name: String) {
        viewModel.onEvent(event: BookEditUiEventMoodEntered(name: name))
    }

    // Collections (admin-only) — select an existing collection only (no free-text creation).
    func setCollectionQuery(_ value: String) {
        viewModel.onEvent(event: BookEditUiEventCollectionSearchQueryChanged(query: value))
    }
    func selectCollectionResult(_ result: RelationSearchResult) {
        guard let match = rawCollectionResults.first(where: { $0.id == result.id }) else { return }
        viewModel.onEvent(event: BookEditUiEventCollectionSelected(collection: match))
    }

    // MARK: - Actions

    func onSave() { viewModel.onEvent(event: BookEditUiEventSave.shared) }
    func onCancel() { viewModel.onEvent(event: BookEditUiEventCancel.shared) }
    func onDismissError() { viewModel.onEvent(event: BookEditUiEventDismissError.shared) }

    // MARK: - State flatten

    private func apply(_ state: BookEditUiState) {
        isLoading = state.isLoading
        title = state.title
        sortTitle = state.sortTitle
        subtitle = state.subtitle
        bookDescription = state.description_
        publisher = state.publisher
        publishYear = state.publishYear
        displayCoverPath = state.displayCoverPath
        coverHash = state.coverHash
        isUploadingCover = state.isUploadingCover
        rawAuthors = state.authors
        rawNarrators = state.narrators
        rawSeries = state.series
        rawGenres = state.genres
        rawTags = state.tags
        rawMoods = state.moods
        rawCollections = state.collections
        authors = state.authors.map { EditableRelation.contributor(name: $0.name) }
        narrators = state.narrators.map { EditableRelation.contributor(name: $0.name) }
        series = state.series.map { EditableRelation.series(name: $0.name, sequence: $0.sequence) }
        genres = state.genres.map { EditableRelation.genre(id: $0.id, name: $0.name) }
        tags = state.tags.map { EditableRelation.tag(id: $0.id, slug: $0.slug) }
        moods = state.moods.map { EditableRelation.mood(id: $0.id, slug: $0.slug) }
        collections = state.collections.map { EditableRelation.collection(id: $0.id, name: $0.name) }
        isAdmin = state.isAdmin
        applySearchState(state)
        hasChanges = state.hasChanges
        isSaving = state.isSaving
        error = state.error
    }

    /// Maps the add-picker search sub-state (per-role contributor, series, genre, tag, mood) from
    /// the shared `BookEditUiState` into native value types, off the SwiftUI diff path.
    private func applySearchState(_ state: BookEditUiState) {
        // Contributors — per-role maps keyed by ContributorRole.
        authorQuery = state.roleSearchQueries[.author] ?? ""
        narratorQuery = state.roleSearchQueries[.narrator] ?? ""
        authorSearching = state.roleSearchLoading[.author] ?? false
        narratorSearching = state.roleSearchLoading[.narrator] ?? false
        rawAuthorResults = state.roleSearchResults[.author] ?? []
        rawNarratorResults = state.roleSearchResults[.narrator] ?? []
        authorResults = rawAuthorResults.map(Self.contributorResult)
        narratorResults = rawNarratorResults.map(Self.contributorResult)

        // Series.
        seriesQuery = state.seriesSearchQuery
        seriesSearching = state.seriesSearchLoading
        rawSeriesResults = state.seriesSearchResults
        seriesResults = rawSeriesResults.map(Self.seriesResult)

        // Genres (select-only — no loading flag in shared state, filtered locally).
        genreQuery = state.genreSearchQuery
        rawGenreResults = state.genreSearchResults
        genreResults = rawGenreResults.map(Self.genreResult)

        // Tags.
        tagQuery = state.tagSearchQuery
        tagSearching = state.tagSearchLoading || state.tagCreating
        rawTagResults = state.tagSearchResults
        tagResults = rawTagResults.map { Self.slugResult(id: $0.id, slug: $0.slug) }

        // Moods.
        moodQuery = state.moodSearchQuery
        moodSearching = state.moodSearchLoading || state.moodCreating
        rawMoodResults = state.moodSearchResults
        moodResults = rawMoodResults.map { Self.slugResult(id: $0.id, slug: $0.slug) }

        // Collections (select-only — no loading flag in shared state, filtered locally like genres).
        collectionQuery = state.collectionSearchQuery
        rawCollectionResults = state.collectionSearchResults
        collectionResults = rawCollectionResults.map { RelationSearchResult(id: $0.id, name: $0.name, subtitle: nil) }
    }

    // MARK: - Result projections (pure, off the diff path)

    private static func contributorResult(_ result: ContributorSearchResult) -> RelationSearchResult {
        RelationSearchResult(
            id: result.id,
            name: result.name,
            subtitle: BookEditFormatting.bookCountSubtitle(Int(result.bookCount))
        )
    }
    private static func seriesResult(_ result: SeriesSearchResult) -> RelationSearchResult {
        RelationSearchResult(
            id: result.id,
            name: result.name,
            subtitle: BookEditFormatting.bookCountSubtitle(Int(result.bookCount))
        )
    }
    private static func genreResult(_ genre: EditableGenre) -> RelationSearchResult {
        RelationSearchResult(id: genre.id, name: genre.name, subtitle: BookEditFormatting.genreParentPath(genre.path))
    }
    private static func slugResult(id: String, slug: String) -> RelationSearchResult {
        RelationSearchResult(id: id, name: BookEditFormatting.tagLabel(slug: slug), subtitle: nil)
    }

    private func applyNav(_ action: BookEditNavAction) {
        switch onEnum(of: action) {
        case .navigateBack: didFinish = true
        case .showSaveSuccess: didFinish = true
        case .unknown: Log.error("Unexpected BookEditNavAction case")
        }
    }
}
