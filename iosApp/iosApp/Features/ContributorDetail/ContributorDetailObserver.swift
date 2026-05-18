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
    private(set) var contributor: Contributor?
    private(set) var roleSections: [RoleSection] = []
    private(set) var bookProgress: [String: Float] = [:]
    private(set) var isDeleting: Bool = false

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

    /// Called when the contributor is deleted — set by the view to pop back.
    var onDeleted: (() -> Void)?

    private let viewModel: ContributorDetailViewModel
    private let bridge = FlowBridge()

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

    func confirmDelete() {
        viewModel.confirmDelete()
    }

    func dismissDeleteError() {
        viewModel.dismissDeleteError()
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
            onDeleted?()
        }
    }

    /// `Map<BookId, Float>` arrives as `[String: KotlinFloat]` over the SKIE boundary.
    private func mapProgress(_ raw: [String: KotlinFloat]) -> [String: Float] {
        raw.mapValues { $0.floatValue }
    }
}
