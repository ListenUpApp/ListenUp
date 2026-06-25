import SwiftUI
@preconcurrency import Shared

/// Observes `ContributorEditViewModel`, flattening `ContributorEditUiState` into
/// `@Observable` properties and dispatching edits as `ContributorEditUiEvent`s.
@Observable
@MainActor
final class ContributorEditObserver {
    private(set) var isLoading: Bool = true
    private(set) var name: String = ""
    /// The contributor biography — stored as `bio` because Swift Export renames the Kotlin
    /// `description` property to `description_` (dodging the Swift `description` clash).
    private(set) var bio: String = ""
    private(set) var website: String = ""
    private(set) var birthDate: String = ""
    private(set) var deathDate: String = ""
    private(set) var imagePath: String?
    private(set) var hasChanges: Bool = false
    private(set) var isSaving: Bool = false
    private(set) var isUploadingImage: Bool = false
    private(set) var error: String?
    private(set) var didFinish: Bool = false

    private let viewModel: ContributorEditViewModel
    private let bridge = FlowBridge()

    init(viewModel: ContributorEditViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.navActions) { [weak self] in self?.applyNav($0) }
    }

    deinit { MainActor.assumeIsolated { bridge.cancelAll() } }

    func loadContributor(contributorId: String) { viewModel.loadContributor(contributorId: contributorId) }

    func onNameChanged(_ value: String) { viewModel.onEvent(event: ContributorEditUiEventNameChanged(name: value)) }
    func onBioChanged(_ value: String) {
        viewModel.onEvent(event: ContributorEditUiEventDescriptionChanged(description: value))
    }
    func onWebsiteChanged(_ value: String) {
        viewModel.onEvent(event: ContributorEditUiEventWebsiteChanged(website: value))
    }
    func onBirthDateChanged(_ value: String) {
        viewModel.onEvent(event: ContributorEditUiEventBirthDateChanged(date: value))
    }
    func onDeathDateChanged(_ value: String) {
        viewModel.onEvent(event: ContributorEditUiEventDeathDateChanged(date: value))
    }
    func onImagePicked(_ data: Data) {
        viewModel.onEvent(event: ContributorEditUiEventUploadImage(
            imageData: data.toKotlinByteArray(),
            filename: "avatar.jpg"
        ))
    }
    func onSave() { viewModel.onEvent(event: ContributorEditUiEventSave.shared) }
    func onCancel() { viewModel.onEvent(event: ContributorEditUiEventCancel.shared) }
    func onDismissError() { viewModel.onEvent(event: ContributorEditUiEventDismissError.shared) }

    private func apply(_ state: ContributorEditUiState) {
        isLoading = state.isLoading
        name = state.name
        bio = state.description_
        website = state.website
        birthDate = state.birthDate
        deathDate = state.deathDate
        imagePath = state.imagePath
        hasChanges = state.hasChanges
        isSaving = state.isSaving
        isUploadingImage = state.isUploadingImage
        error = state.error
    }

    private func applyNav(_ action: ContributorEditNavAction) {
        switch onEnum(of: action) {
        case .navigateBack, .saveSuccess: didFinish = true
        }
    }
}
