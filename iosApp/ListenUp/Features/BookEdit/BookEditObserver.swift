import SwiftUI
import Shared

/// One contributor-role section on the book-edit form (Author, Narrator, Editor, …). Built from the
/// shared VM's `orderedVisibleRoles` + the bridge-safe role-generic accessors, so every visible role
/// renders dynamically — no hardcoded Author/Narrator. Native value type: no bridged Kotlin object
/// reaches a `ForEach`.
struct BookEditRoleSection: Identifiable {
    /// The role's `apiValue` string — the stable id AND the only role handle Swift keeps. We never
    /// store a bridged `ContributorRole` (reading enum values out of Kotlin traps); the observer
    /// reconstructs one transiently from this string when it must call into Kotlin.
    let id: String
    let title: String
    let contributors: [EditableRelation]
    let query: String
    let results: [RelationSearchResult]
    let searching: Bool
    /// Author is always present and can't be removed; every other role's section can be dismissed.
    let canRemove: Bool
}

/// A role the user can add a section for (a role not currently visible). Native projection of a
/// `ContributorRole` for the "Add role" menu.
struct AddableRole: Identifiable {
    let id: String            // role.apiValue — the only role handle Swift keeps (see BookEditRoleSection)
    let title: String
}

/// One ISO 639-1 language choice (code + display name) for the language picker. Native projection
/// of the shared `LanguageOption`, so no bridged Kotlin object reaches the SwiftUI `Picker`.
struct LanguageChoice: Identifiable, Hashable {
    let code: String
    let name: String
    var id: String { code }
}

/// Observes `BookEditViewModel`, flattening `BookEditUiState` into `@Observable`
/// properties and dispatching edits as `BookEditUiEvent`s. `NavigateBack` flips
/// `didFinish` so the sheet dismisses.
///
/// The relational lists (contributors, series, genres, tags, moods, collections) are mapped to
/// native `EditableRelation` chips and `RelationSearchResult` rows at the observer boundary — no
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
    /// ISO 639-1 language code ("" = unset). Bound to a picker over `languageOptions`.
    private(set) var language: String = ""
    private(set) var isbn: String = ""
    private(set) var asin: String = ""
    private(set) var abridged: Bool = false
    /// "Added to library" timestamp; `nil` = use the detected value. Kotlin `Long?` epoch millis
    /// bridges to Swift `Int64?`, mapped to/from `Date` here.
    private(set) var addedAt: Date?

    /// ISO 639-1 language choices (common languages first) for the language picker — a native
    /// projection of the shared `Language.getAllLanguages()`, built once.
    let languageOptions: [LanguageChoice] = Language.shared.getAllLanguages().map {
        LanguageChoice(code: $0.code, name: $0.name)
    }

    // Cover
    private(set) var displayCoverPath: String?
    private(set) var coverHash: String?
    private(set) var isUploadingCover: Bool = false

    // Relations — native projections fed to the views. No bridged Kotlin object ever reaches a
    // ForEach (it would re-bridge on every diff); the mapping happens once in `apply`.
    /// The visible contributor-role sections, in stable role order. Replaces the old hardcoded
    /// author/narrator pair — every role the book has (or the user adds) renders here.
    private(set) var roleSections: [BookEditRoleSection] = []
    /// Roles not yet shown, offered by the "Add role" menu.
    private(set) var addableRoles: [AddableRole] = []
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
    // Contributors are keyed by role.apiValue (a String — NEVER a bridged ContributorRole dict key,
    // which traps; see applySearchState).
    private var rawContributorsByRole: [String: [EditableContributor]] = [:]
    private var rawResultsByRole: [String: [ContributorSearchResult]] = [:]
    private var rawSeries: [EditableSeries] = []
    private var rawGenres: [EditableGenre] = []
    private var rawTags: [EditableTag] = []
    private var rawMoods: [EditableMood] = []
    private var rawCollections: [EditableCollection] = []

    // Add-picker search sub-state — native projections fed to the result rows. Queries are echoed
    // from shared state so the field stays the single source of truth across re-emits.
    private(set) var seriesQuery: String = ""
    private(set) var genreQuery: String = ""
    private(set) var tagQuery: String = ""
    private(set) var moodQuery: String = ""
    private(set) var collectionQuery: String = ""

    private(set) var seriesResults: [RelationSearchResult] = []
    private(set) var genreResults: [RelationSearchResult] = []
    private(set) var tagResults: [RelationSearchResult] = []
    private(set) var moodResults: [RelationSearchResult] = []
    private(set) var collectionResults: [RelationSearchResult] = []

    private(set) var seriesSearching: Bool = false
    private(set) var tagSearching: Bool = false
    private(set) var moodSearching: Bool = false

    // Raw search results retained for the id→object lookup when a result row is tapped, off the
    // diff path (never iterated by a ForEach) so they don't re-bridge.
    private var rawSeriesResults: [SeriesSearchResult] = []
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
    /// A blank code clears the language selection back to "unset".
    func setLanguage(_ code: String) {
        viewModel.onEvent(event: BookEditUiEventLanguageChanged(code: code.isEmpty ? nil : code))
    }
    func setIsbn(_ value: String) { viewModel.onEvent(event: BookEditUiEventIsbnChanged(isbn: value)) }
    func setAsin(_ value: String) { viewModel.onEvent(event: BookEditUiEventAsinChanged(asin: value)) }
    func setAbridged(_ value: Bool) { viewModel.onEvent(event: BookEditUiEventAbridgedChanged(abridged: value)) }
    /// `nil` clears the timestamp back to "use detected value".
    func setAddedAt(_ date: Date?) {
        viewModel.onEvent(event: BookEditUiEventAddedAtChanged(
            epochMillis: date.map { Int64($0.timeIntervalSince1970 * 1000) }
        ))
    }

    // MARK: - Cover intents

    func onCoverPicked(_ data: Data) {
        viewModel.onEvent(event: BookEditUiEventUploadCover(
            imageData: data.toKotlinByteArray(),
            filename: "cover.jpg"
        ))
    }

    // MARK: - Relation remove intents

    func removeContributor(_ relation: EditableRelation, roleApiValue: String) {
        guard let role = Self.roleFromApiValue(roleApiValue),
              let contributor = rawContributorsByRole[roleApiValue]?.first(where: { $0.name == relation.id })
        else { return }
        viewModel.onEvent(event: BookEditUiEventRemoveContributor(contributor: contributor, role: role))
    }

    /// Add a section for a role not currently shown; remove one (any role but Author, which is fixed).
    func addRole(roleApiValue: String) {
        guard let role = Self.roleFromApiValue(roleApiValue) else { return }
        viewModel.onEvent(event: BookEditUiEventAddRoleSection(role: role))
    }
    func removeRole(roleApiValue: String) {
        guard let role = Self.roleFromApiValue(roleApiValue) else { return }
        viewModel.onEvent(event: BookEditUiEventRemoveRoleSection(role: role))
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

    // Contributors (per role) — search, select an existing result, or enter a new name. Roles are
    // passed as apiValue strings; the enum is reconstructed only to hand into the Kotlin event.
    func setContributorQuery(_ value: String, roleApiValue: String) {
        guard let role = Self.roleFromApiValue(roleApiValue) else { return }
        viewModel.onEvent(event: BookEditUiEventRoleSearchQueryChanged(role: role, query: value))
    }
    func selectContributorResult(_ result: RelationSearchResult, roleApiValue: String) {
        guard let role = Self.roleFromApiValue(roleApiValue),
              let match = rawResultsByRole[roleApiValue]?.first(where: { $0.id == result.id })
        else { return }
        viewModel.onEvent(event: BookEditUiEventRoleContributorSelected(role: role, result: match))
    }
    func enterContributor(_ name: String, roleApiValue: String) {
        guard let role = Self.roleFromApiValue(roleApiValue) else { return }
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
        language = state.language ?? ""
        isbn = state.isbn
        asin = state.asin
        abridged = state.abridged
        addedAt = state.addedAt.map { Date(timeIntervalSince1970: Double($0) / 1000) }
        displayCoverPath = state.displayCoverPath
        coverHash = state.coverHash
        isUploadingCover = state.isUploadingCover
        rawSeries = state.series
        rawGenres = state.genres
        rawTags = state.tags
        rawMoods = state.moods
        rawCollections = state.collections
        applyRoleSections(state)
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
        collectionResults = rawCollectionResults.map(Self.collectionResult)
    }

    /// Builds the dynamic contributor-role sections from the shared VM's visible roles.
    ///
    /// Roles arrive as `apiValue` strings (`orderedVisibleRoleApiValues`), NOT as bridged
    /// `ContributorRole` values: Swift Export boxes the elements of a `List<ContributorRole>` as
    /// opaque existentials that trap when cast back to the enum ("Could not cast … to
    /// …ContributorRole"). We reconstruct a Swift `ContributorRole` locally from the string, which
    /// then passes safely into the Kotlin accessors as a function argument (the direction that
    /// bridges). Raw Kotlin lists are stashed by `apiValue` for the id→object lookup on remove/select.
    private func applyRoleSections(_ state: BookEditUiState) {
        var sections: [BookEditRoleSection] = []
        var rawContributors: [String: [EditableContributor]] = [:]
        var rawResults: [String: [ContributorSearchResult]] = [:]
        for apiValue in state.orderedVisibleRoleApiValues {
            guard let role = Self.roleFromApiValue(apiValue) else { continue }
            let contributors = state.contributorsForRole(role: role)
            let results = state.searchResultsForRole(role: role)
            rawContributors[apiValue] = contributors
            rawResults[apiValue] = results
            sections.append(
                BookEditRoleSection(
                    id: apiValue,
                    title: Self.roleTitle(role),
                    contributors: contributors.map { EditableRelation.contributor(name: $0.name) },
                    query: state.searchQueryForRole(role: role),
                    results: results.map(Self.contributorResult),
                    searching: state.searchLoadingForRole(role: role),
                    canRemove: apiValue != "author"
                )
            )
        }
        roleSections = sections
        rawContributorsByRole = rawContributors
        rawResultsByRole = rawResults
        addableRoles = state.availableRoleApiValuesToAdd.compactMap { apiValue in
            guard let role = Self.roleFromApiValue(apiValue) else { return nil }
            return AddableRole(id: apiValue, title: Self.roleTitle(role))
        }
    }

    /// Reconstructs a Swift `ContributorRole` from its `apiValue` string. A Swift-constructed enum
    /// value passes safely into the Kotlin accessors/events (the bridge-safe function-argument
    /// direction), unlike an enum value read out of a bridged `List<ContributorRole>`, which traps.
    static func roleFromApiValue(_ apiValue: String) -> ContributorRole? {
        switch apiValue {
        case "author": return .author
        case "narrator": return .narrator
        case "editor": return .editor
        case "translator": return .translator
        case "foreword": return .foreword
        case "introduction": return .introduction
        case "afterword": return .afterword
        case "producer": return .producer
        case "adapter": return .adapter
        case "illustrator": return .illustrator
        default: return nil
        }
    }

    /// Localized display name for a contributor role. Kept in Swift (not the Kotlin `displayName`
    /// extension, which doesn't reliably cross Swift Export) so the strings resolve from the catalog.
    static func roleTitle(_ role: ContributorRole) -> String {
        switch role {
        case .author: return String(localized: "book.role_author")
        case .narrator: return String(localized: "book.role_narrator")
        case .editor: return String(localized: "book.role_editor")
        case .translator: return String(localized: "book.role_translator")
        case .foreword: return String(localized: "book.role_foreword")
        case .introduction: return String(localized: "book.role_introduction")
        case .afterword: return String(localized: "book.role_afterword")
        case .producer: return String(localized: "book.role_producer")
        case .adapter: return String(localized: "book.role_adapter")
        case .illustrator: return String(localized: "book.role_illustrator")
        default: return ""
        }
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
    private static func collectionResult(_ collection: EditableCollection) -> RelationSearchResult {
        RelationSearchResult(id: collection.id, name: collection.name, subtitle: nil)
    }

    private func applyNav(_ action: BookEditNavAction) {
        switch onEnum(of: action) {
        case .navigateBack: didFinish = true
        case .showSaveSuccess: didFinish = true
        case .unknown: Log.error("Unexpected BookEditNavAction case")
        }
    }
}
