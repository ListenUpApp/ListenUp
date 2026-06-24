import SwiftUI
@preconcurrency import Shared

/// Observes `BookEditViewModel`, flattening `BookEditUiState` into `@Observable`
/// properties and dispatching edits as `BookEditUiEvent`s. `NavigateBack` flips
/// `didFinish` so the sheet dismisses.
///
/// The relational lists (contributors, series, genres, tags) are exposed as the
/// shared Kotlin `Editable*` domain types directly — they cross the SKIE seam as
/// value types the SwiftUI rows render and key on. Display + remove is wired here;
/// the searchable add-pickers are intentionally out of scope for this slice.
@Observable
@MainActor
final class BookEditObserver {
    private(set) var isLoading: Bool = true

    // Text fields
    private(set) var title: String = ""
    private(set) var sortTitle: String = ""
    private(set) var subtitle: String = ""
    /// The book description — stored as `bookDescription` to avoid the NSObject
    /// `description` property clash that SKIE bridges as `description_`.
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

    // Raw Kotlin lists retained for the id→object lookup when a chip's remove button is tapped.
    // Held off the SwiftUI diff path (never iterated by a ForEach), so they don't re-bridge.
    private var rawAuthors: [EditableContributor] = []
    private var rawNarrators: [EditableContributor] = []
    private var rawSeries: [EditableSeries] = []
    private var rawGenres: [EditableGenre] = []
    private var rawTags: [EditableTag] = []
    private var rawMoods: [EditableMood] = []

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

    deinit { MainActor.assumeIsolated { bridge.cancelAll() } }

    func loadBook(bookId: String) { viewModel.loadBook(bookId: bookId) }

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
        authors = state.authors.map { EditableRelation.contributor(name: $0.name) }
        narrators = state.narrators.map { EditableRelation.contributor(name: $0.name) }
        series = state.series.map { EditableRelation.series(name: $0.name, sequence: $0.sequence) }
        genres = state.genres.map { EditableRelation.genre(id: $0.id, name: $0.name) }
        tags = state.tags.map { EditableRelation.tag(id: $0.id, slug: $0.slug) }
        moods = state.moods.map { EditableRelation.mood(id: $0.id, slug: $0.slug) }
        hasChanges = state.hasChanges
        isSaving = state.isSaving
        error = state.error
    }

    private func applyNav(_ action: BookEditNavAction) {
        switch onEnum(of: action) {
        case .navigateBack: didFinish = true
        case .showSaveSuccess: didFinish = true
        }
    }
}
