import SwiftUI
import Shared

/// Observes `ContributorDetailViewModel` — flattens the sealed
/// `ContributorDetailUiState` into flat `@Observable` properties, plus a
/// `navActions` secondary flow. Thin over `FlowBridge`.
@Observable
@MainActor
final class ContributorDetailObserver {
    // MARK: - Flattened state

    private(set) var isLoading: Bool = true
    private(set) var error: String?
    private(set) var roleSections: [RoleSectionRow] = []
    private(set) var bookProgress: [String: Float] = [:]
    private(set) var isDeleting: Bool = false
    private(set) var series: [SeriesRow] = []
    private(set) var totalDuration: String = ""
    private(set) var bookCount: Int = 0
    private(set) var roles: [RoleChip.Kind] = []

    /// Whether the delete-confirmation dialog should show. Wrapper-held input
    /// state — the ViewModel has no "confirming" state; `confirmDelete()` deletes
    /// immediately, so the confirmation step lives here.
    private(set) var showDeleteConfirmation: Bool = false

    // MARK: - Projected from `Contributor`

    // Native value projections set once in `apply`, never re-bridged in `body`. The view
    // reads `aliases` twice per render — as computed accessors over the raw Kotlin
    // `Contributor` each read re-bridged the full alias list across the K/N boundary.
    private(set) var name: String = ""
    private(set) var bio: String?
    private(set) var imagePath: String?
    private(set) var aliases: [String] = []
    private(set) var birthDate: String?
    private(set) var deathDate: String?
    private(set) var website: String?

    private let viewModel: ContributorDetailViewModel
    private let bridge = FlowBridge()
    /// Fired once the ViewModel signals deletion completion via `navActions`.
    private var onDeletedCallback: (() -> Void)?

    init(viewModel: ContributorDetailViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.navActions) { [weak self] in self?.applyNavAction($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func loadContributor(contributorId: String) {
        viewModel.loadContributor(contributorId: contributorId)
    }

    /// Show the delete-confirmation dialog.
    func onDeleteContributor() {
        showDeleteConfirmation = true
    }

    /// Dismiss the delete-confirmation dialog without deleting.
    func onDismissDelete() {
        showDeleteConfirmation = false
    }

    /// Confirm deletion. `onDeleted` fires once the ViewModel signals completion
    /// via its `navActions` flow.
    func onConfirmDelete(_ onDeleted: @escaping () -> Void) {
        onDeletedCallback = onDeleted
        showDeleteConfirmation = false
        viewModel.confirmDelete()
    }

    // MARK: - State mapping

    private func apply(_ state: ContributorDetailUiState) {
        switch onEnum(of: state) {
        case .idle, .loading:
            isLoading = true
            error = nil
        case .ready(let r):
            isLoading = false
            error = nil
            let contributor = r.contributor
            name = contributor.name
            bio = contributor.description_
            imagePath = contributor.imagePath
            aliases = Array(contributor.aliases)
            birthDate = contributor.birthDate
            deathDate = contributor.deathDate
            website = contributor.website
            roleSections = r.roleSections.map { RoleSectionRow($0) }
            bookProgress = mapProgress(r.bookProgress)
            isDeleting = r.isDeleting
            series = r.series.map { SeriesRow($0) }
            totalDuration = r.formatTotalDuration()
            bookCount = Int(r.bookCount)
            roles = r.roleSections.map { roleKind(for: $0.role) }
        case .error(let err):
            isLoading = false
            error = err.message
        case .unknown:
            Log.error("Unexpected ContributorDetailUiState case")
            isLoading = false
            error = String(localized: "common.something_went_wrong")
        }
    }

    private func applyNavAction(_ action: ContributorDetailNavAction) {
        switch onEnum(of: action) {
        case .deleted:
            onDeletedCallback?()
        case .unknown:
            Log.error("Unexpected ContributorDetailNavAction case")
        }
    }

    /// Maps a role string to its chip kind. Author/narrator get specific chips;
    /// anything else uses the title-cased role as the label.
    private func roleKind(for role: String) -> RoleChip.Kind {
        switch role.lowercased() {
        case "author": .author
        case "narrator": .narrator
        default: .other(role.prefix(1).uppercased() + role.dropFirst().lowercased())
        }
    }

    /// `Map<BookId, Float>` arrives as `[BookId: Float]` over the Swift Export
    /// boundary — the `BookId` value-class key bridges as its wrapper type. Keys are
    /// normalized to the book-id string the UI looks up by.
    private func mapProgress(_ raw: [BookId: Float]) -> [String: Float] {
        var result: [String: Float] = [:]
        for (key, value) in raw {
            result[key.value] = value
        }
        return result
    }
}
