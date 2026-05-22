import SwiftUI
@preconcurrency import Shared

/// Observes `ContributorDetailViewModel` — flattens the sealed
/// `ContributorDetailUiState` into flat `@Observable` properties, plus a
/// `navActions` secondary flow. Thin over `FlowBridge`.
@Observable
@MainActor
final class ContributorDetailObserver {
    // MARK: - Flattened state

    private(set) var isLoading: Bool = true
    private(set) var error: String?
    private(set) var contributor: Contributor?
    private(set) var roleSections: [RoleSection] = []
    private(set) var bookProgress: [String: Float] = [:]
    private(set) var isDeleting: Bool = false

    /// Whether the delete-confirmation dialog should show. Wrapper-held input
    /// state — the ViewModel has no "confirming" state; `confirmDelete()` deletes
    /// immediately, so the confirmation step lives here.
    private(set) var showDeleteConfirmation: Bool = false

    // MARK: - Derived from `contributor`

    var name: String { contributor?.name ?? "" }
    var bio: String? { contributor?.description_ }
    var imagePath: String? { contributor?.imagePath }
    var imageBlurHash: String? { contributor?.imageBlurHash }
    var aliases: [String] { contributor.map { Array($0.aliases) } ?? [] }
    var birthDate: String? { contributor?.birthDate }
    var deathDate: String? { contributor?.deathDate }
    var website: String? { contributor?.website }
    var totalBookCount: Int { roleSections.reduce(0) { $0 + Int($1.bookCount) } }

    private let viewModel: ContributorDetailViewModel
    private let bridge = FlowBridge()
    /// Fired once the ViewModel signals deletion completion via `navActions`.
    private var onDeletedCallback: (() -> Void)?

    init(viewModel: ContributorDetailViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.navActions) { [weak self] in self?.applyNavAction($0) }
    }

    func stopObserving() {
        bridge.cancelAll()
    }

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
            contributor = r.contributor
            roleSections = Array(r.roleSections)
            bookProgress = mapProgress(r.bookProgress)
            isDeleting = r.isDeleting
        case .error(let e):
            isLoading = false
            error = e.message
        }
    }

    private func applyNavAction(_ action: ContributorDetailNavAction) {
        switch onEnum(of: action) {
        case .deleted:
            onDeletedCallback?()
        }
    }

    /// `Map<BookId, Float>` arrives as `[AnyHashable: KotlinFloat]` over the SKIE
    /// boundary — the `BookId` value-class key bridges as `AnyHashable`. Keys are
    /// normalized to the book-id string the UI looks up by.
    private func mapProgress(_ raw: [AnyHashable: KotlinFloat]) -> [String: Float] {
        var result: [String: Float] = [:]
        for (key, value) in raw {
            result[String(describing: key.base)] = value.floatValue
        }
        return result
    }
}
