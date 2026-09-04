import SwiftUI
import Shared

/// Observes `BulkEditViewModel`, flattening `BulkEditUiState` into `@Observable` properties and
/// forwarding the form's edits back as setter calls.
///
/// Everything the views read is a native Swift value — the preview rows in particular, because a
/// Swift-Export-bridged Kotlin object re-bridges every property read on every SwiftUI diff (rule 8).
/// The mapping happens once, here, in `apply`.
///
/// **Errors are owned by this screen, deliberately.** A `Failed` event becomes `error` and
/// `BulkEditView` renders it as an alert rather than as a passing message on the app-wide host: a
/// bulk edit that stopped part-way is a thing to read and acknowledge, not to glance at. The shared
/// ViewModel emits to both the bus and the event channel, so the two are not in competition — the
/// global surface still catches anything this screen does not.
@Observable
@MainActor
final class BulkEditObserver {
    // MARK: - Flattened state

    private(set) var isLoading = true

    /// How many of the selected books actually loaded — the books Apply will touch.
    private(set) var bookCount = 0
    /// How many the user selected. Larger than `bookCount` when one could not be read.
    private(set) var requestedCount = 0

    private(set) var publisher = ""
    private(set) var year = ""
    private(set) var language = ""

    /// Hints for the untouched fields: the value the whole selection agrees on, or "Multiple values".
    private(set) var publisherPlaceholder = ""
    private(set) var yearPlaceholder = ""
    private(set) var languagePlaceholder = ""

    /// How many books would change at least one field — the number on the Apply button.
    private(set) var changedBookCount = 0
    private(set) var canApply = false
    private(set) var isApplying = false
    private(set) var preview: [BulkEditPreviewLine] = []

    // MARK: - Relation state

    /// What has been chosen so far, projected from the instruction list — never held separately, so
    /// "the chip is on screen" and "an instruction exists" stay one fact rather than two that have
    /// to be kept in step.
    private(set) var seriesChips: [EditableRelation] = []
    private(set) var contributorChips: [EditableRelation] = []
    private(set) var genreChips: [EditableRelation] = []
    private(set) var tagChips: [EditableRelation] = []
    private(set) var moodChips: [EditableRelation] = []

    /// The two searched pickers' queries. Written through the setters below rather than a `didSet`
    /// observer: `@Observable` rewrites stored properties, and a property observer that also has to
    /// reach the ViewModel is a side effect hidden inside an assignment.
    private(set) var seriesQuery = ""
    private(set) var contributorQuery = ""

    /// The three locally-filtered queries. Plain state — nothing outside this class reads them.
    var genreQuery = ""
    var tagQuery = ""
    var moodQuery = ""

    /// The role the *next* contributor is credited in. Held as an `apiValue` string: reading an enum
    /// out of a bridged `List<ContributorRole>` traps, which is why `roleFromApiValue` exists.
    var pendingRoleApiValue = "author"

    private(set) var seriesResults: [RelationSearchResult] = []
    private(set) var contributorResults: [RelationSearchResult] = []
    private var genreCatalogue: [RelationSearchResult] = []
    private var tagCatalogue: [RelationSearchResult] = []
    private var moodCatalogue: [RelationSearchResult] = []

    /// The raw instruction values, so a removal can rebuild the list the ViewModel expects.
    private var chosenSeries: BookSeriesInput?
    private var chosenContributors: [BookContributorInput] = []
    private var chosenGenres: [BookGenreInput] = []
    private var chosenTags: [String] = []
    private var chosenMoods: [String] = []

    /// What each locally-filtered picker offers, narrowed against what is already chosen.
    var genreOffers: [RelationSearchResult] {
        BulkEditMapping.narrow(genreCatalogue, query: genreQuery, excluding: Set(chosenGenres.map(\.genreId.value)))
    }
    var tagOffers: [RelationSearchResult] {
        BulkEditMapping.narrow(tagCatalogue, query: tagQuery, excluding: Set(chosenTags))
    }
    var moodOffers: [RelationSearchResult] {
        BulkEditMapping.narrow(moodCatalogue, query: moodQuery, excluding: Set(chosenMoods))
    }

    private(set) var error: String?
    /// Flips once the apply finishes, which dismisses the sheet.
    private(set) var didFinish = false
    /// How many books the finished apply actually changed.
    private(set) var appliedCount = 0

    // MARK: - Dependencies

    private let viewModel: BulkEditViewModel
    private let bridge = FlowBridge()

    init(viewModel: BulkEditViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.applyState($0) }
        bridge.bind(viewModel.events) { [weak self] in self?.applyEvent($0) }
        // Series and contributors are *searched*: the ViewModel debounces at 300ms with a
        // two-character floor, so the query goes to it rather than being filtered here.
        bridge.bind(viewModel.seriesMatches) { [weak self] matches in
            self?.seriesResults = matches.map { RelationSearchResult(id: $0.id, name: $0.name, subtitle: nil) }
        }
        bridge.bind(viewModel.contributorMatches) { [weak self] matches in
            self?.contributorResults = matches.map { RelationSearchResult(id: $0.id, name: $0.name, subtitle: nil) }
        }
        // Genres, tags and moods arrive whole and are narrowed locally, so these pickers work with
        // the network off.
        bridge.bind(viewModel.genres) { [weak self] genres in
            self?.genreCatalogue = genres.map {
                RelationSearchResult(id: $0.id, name: $0.name, subtitle: $0.parentPath)
            }
        }
        bridge.bind(viewModel.tags) { [weak self] tags in
            self?.tagCatalogue = tags.map { RelationSearchResult(id: $0.name, name: $0.name, subtitle: nil) }
        }
        bridge.bind(viewModel.moods) { [weak self] moods in
            self?.moodCatalogue = moods.map { RelationSearchResult(id: $0.name, name: $0.name, subtitle: nil) }
        }
    }

    // Isolated deinit (SE-0371): there is no ViewModelStore on iOS to call `onCleared`, so this
    // observer closes the VM itself — otherwise the selection load keeps its coroutine scope alive
    // after the sheet is gone.
    isolated deinit {
        bridge.cancelAll()
        viewModel.close()
    }

    // MARK: - Field intents

    /// The publisher field changed. Blank removes the instruction rather than writing an empty value.
    func setPublisher(_ value: String) { viewModel.setPublisher(publisher: value) }

    /// The year field changed. Digits only, max 4 — the shared setter refuses a year outside
    /// `BookUpdate.MIN_YEAR...MAX_YEAR` rather than throwing, so filtering here keeps the field from
    /// showing text the ViewModel never accepted.
    func setYear(_ value: String) {
        let digits = String(value.filter(\.isNumber).prefix(4))
        viewModel.setYear(year: Int32(digits))
    }

    /// The language field changed. Blank removes the instruction.
    func setLanguage(_ value: String) { viewModel.setLanguage(language: value) }

    // MARK: - Relation intents

    /// Push the series query into the ViewModel's debounced search.
    func setSeriesQuery(_ value: String) {
        seriesQuery = value
        viewModel.setSeriesQuery(query: value)
    }

    /// As `setSeriesQuery`, for people.
    func setContributorQuery(_ value: String) {
        contributorQuery = value
        viewModel.setContributorQuery(query: value)
    }

    /// One series. `AddToSeries` carries a single sequence number and the planner drops it — a
    /// shared number across forty books would make every one of them Book 1 — so this field offers
    /// no sequence and does not pretend to.
    func pickSeries(_ result: RelationSearchResult) {
        viewModel.setSeries(
            series: BookSeriesInput(
                id: SeriesId(value: result.id),
                name: result.name,
                // Swift Export does not carry Kotlin default arguments, so every parameter is
                // passed explicitly. No sequence: `AddToSeries` carries one number for the whole
                // selection, which would make every book in it Book 1.
                position: nil,
                isPrimary: false
            )
        )
        setSeriesQuery("")
    }

    /// A series the library has never held. No id: the server resolves-or-creates by name, the same
    /// path the scanner and the single-book editor take.
    func createSeries(named name: String) {
        viewModel.setSeries(
            series: BookSeriesInput(id: nil, name: name, position: nil, isPrimary: false)
        )
        setSeriesQuery("")
    }

    func removeSeries() { viewModel.setSeries(series: nil) }

    func pickContributor(_ result: RelationSearchResult) {
        addContributor(
            BookContributorInput(
                id: ContributorId(value: result.id),
                name: result.name,
                role: pendingRoleApiValue,
                // No alternate credit line for a bulk add: one string across forty books would be
                // wrong for most of them.
                creditedAs: nil,
                // A per-book ordinal the planner renumbers, so any value here is arbitrary; zero
                // says "let the planner decide" without pretending otherwise.
                position: 0
            )
        )
    }

    /// A narrator the library has never seen is a normal thing to credit across a box set, and
    /// refusing it would send someone to edit forty books one at a time.
    func createContributor(named name: String) {
        addContributor(
            BookContributorInput(
                id: nil,
                name: name,
                role: pendingRoleApiValue,
                creditedAs: nil,
                position: 0
            )
        )
    }

    func removeContributor(_ chip: EditableRelation) {
        let remaining = chosenContributors.filter {
            BulkEditMapping.contributorChip(name: $0.name, roleApiValue: $0.role).id != chip.id
        }
        viewModel.setContributors(contributors: remaining)
    }

    func pickGenre(_ result: RelationSearchResult) {
        viewModel.setGenres(genres: chosenGenres + [BookGenreInput(genreId: GenreId(value: result.id))])
        genreQuery = ""
    }

    func removeGenre(_ chip: EditableRelation) {
        viewModel.setGenres(genres: chosenGenres.filter { $0.genreId.value != chip.id })
    }

    func pickTag(_ result: RelationSearchResult) {
        viewModel.setTags(names: chosenTags + [result.name])
        tagQuery = ""
    }

    func removeTag(_ chip: EditableRelation) {
        viewModel.setTags(names: chosenTags.filter { $0 != chip.id })
    }

    func pickMood(_ result: RelationSearchResult) {
        viewModel.setMoods(names: chosenMoods + [result.name])
        moodQuery = ""
    }

    func removeMood(_ chip: EditableRelation) {
        viewModel.setMoods(names: chosenMoods.filter { $0 != chip.id })
    }

    /// Dedupe on name **and** role: the same person in two roles is two credits, the same person
    /// twice in one role is one.
    private func addContributor(_ addition: BookContributorInput) {
        let alreadyCredited = chosenContributors.contains {
            $0.name.caseInsensitiveCompare(addition.name) == .orderedSame && $0.role == addition.role
        }
        if !alreadyCredited {
            viewModel.setContributors(contributors: chosenContributors + [addition])
        }
        setContributorQuery("")
    }

    /// Apply every instruction to every loaded book.
    func apply() { viewModel.apply() }

    // MARK: - State mapping

    private func applyState(_ state: BulkEditUiState) {
        switch onEnum(of: state) {
        case .loading:
            isLoading = true
        case .editing(let editing):
            isLoading = false
            bookCount = Int(editing.bookCount)
            requestedCount = Int(editing.requestedCount)
            publisher = editing.publisherInput
            year = editing.yearInput
            language = editing.languageInput
            publisherPlaceholder = BulkEditFormatting.placeholder(shared: editing.sharedPublisher)
            yearPlaceholder = BulkEditFormatting.placeholder(
                shared: editing.sharedPublishYear.map { String(Int($0)) }
            )
            languagePlaceholder = BulkEditFormatting.placeholder(shared: editing.sharedLanguage)
            changedBookCount = Int(editing.changedBookCount)
            canApply = editing.canApply
            isApplying = editing.isApplying
            preview = BulkEditMapping.previewLines(Array(editing.preview), bookCount: Int(editing.bookCount))

            chosenSeries = editing.seriesInput
            chosenContributors = Array(editing.contributorInput)
            chosenGenres = Array(editing.genreInput)
            chosenTags = Array(editing.tagInput)
            chosenMoods = Array(editing.moodInput)

            seriesChips = chosenSeries.map { [EditableRelation(id: $0.name, label: $0.name)] } ?? []
            contributorChips = chosenContributors.map {
                BulkEditMapping.contributorChip(name: $0.name, roleApiValue: $0.role)
            }
            genreChips = chosenGenres.map {
                BulkEditMapping.genreChip(id: $0.genreId.value, catalogue: genreCatalogue)
            }
            tagChips = chosenTags.map(BulkEditMapping.nameChip)
            moodChips = chosenMoods.map(BulkEditMapping.nameChip)
        case .unknown:
            // Swift cannot switch a Kotlin sealed interface exhaustively, so this branch is real
            // rather than unreachable: a state this build does not know about leaves the form as it
            // was instead of blanking it, and says so in the log.
            Log.error("Unexpected BulkEditUiState case")
        }
    }

    private func applyEvent(_ event: BulkEditEvent) {
        switch onEnum(of: event) {
        case .applied(let applied):
            appliedCount = Int(applied.changedCount)
            didFinish = true
        case .failed(let failed):
            appliedCount = Int(failed.appliedCount)
            error = BulkEditFormatting.failureMessage(
                reason: failed.error.message,
                appliedCount: Int(failed.appliedCount)
            )
        case .unknown:
            Log.error("Unexpected BulkEditEvent case")
        }
    }

    /// Dismiss the failure alert. The books already committed stand — there is nothing to undo.
    func dismissError() { error = nil }
}
