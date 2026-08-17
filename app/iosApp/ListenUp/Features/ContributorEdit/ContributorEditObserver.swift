import SwiftUI
import Shared

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
    /// Path to render for the avatar: the staged (picked-but-unsaved) local file when present,
    /// otherwise the contributor's image. Mirrors `BookEditObserver.displayCoverPath`.
    private(set) var displayImagePath: String?
    private(set) var hasChanges: Bool = false
    private(set) var isSaving: Bool = false
    private(set) var isUploadingImage: Bool = false
    private(set) var error: String?
    private(set) var didFinish: Bool = false
    /// Set alongside `didFinish` when a merge committed: the id of the contributor that survived it.
    /// After a rename-collision merge the contributor this screen was editing has been deleted and
    /// the survivor is elsewhere; after an alias merge the survivor is this contributor itself.
    private(set) var mergedIntoContributorId: String?
    private(set) var aliases: [String] = []
    private(set) var mergeQuery: String = ""
    private(set) var mergeCandidates: [MergeCandidate] = []
    /// Non-nil while Save is held back because the typed name matches an existing contributor
    /// (punctuation/spacing-insensitive). Drives the merge-or-keep-separate alert.
    private(set) var renameCollisionCandidate: MergeCandidate?

    private let viewModel: ContributorEditViewModel
    private let bridge = FlowBridge()

    init(viewModel: ContributorEditViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.navActions) { [weak self] in self?.applyNav($0) }
        bridge.bind(viewModel.mergeCandidates) { [weak self] candidates in
            self?.mergeCandidates = candidates.map(MergeCandidate.init)
        }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

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
    func onMergeQueryChange(_ value: String) { viewModel.onMergeQueryChange(query: value) }
    /// Tells the VM the merge picker is open — candidate computation runs only while it is.
    func onMergeDialogOpened() { viewModel.onEvent(event: ContributorEditUiEventMergeDialogOpened.shared) }
    /// Tells the VM the merge picker closed; candidates stop computing and the query clears.
    func onMergeDialogDismissed() { viewModel.onEvent(event: ContributorEditUiEventMergeDialogDismissed.shared) }
    func onMergeInto(_ targetId: String) {
        viewModel.onEvent(event: ContributorEditUiEventMergeInto(targetId: ContributorId(value: targetId)))
    }
    func onUnmergeAlias(_ aliasName: String) {
        viewModel.onEvent(event: ContributorEditUiEventUnmergeAlias(aliasName: aliasName))
    }
    func onConfirmMergeOnRename() { viewModel.onEvent(event: ContributorEditUiEventConfirmMergeOnRename.shared) }
    func onKeepSeparateOnRename() { viewModel.onEvent(event: ContributorEditUiEventKeepSeparateOnRename.shared) }
    func onDismissRenameCollision() { viewModel.onEvent(event: ContributorEditUiEventDismissRenameCollision.shared) }

    private func apply(_ state: ContributorEditUiState) {
        isLoading = state.isLoading
        name = state.name
        bio = state.description_
        website = state.website
        birthDate = state.birthDate
        deathDate = state.deathDate
        imagePath = state.imagePath
        displayImagePath = state.displayImagePath
        hasChanges = state.hasChanges
        isSaving = state.isSaving
        isUploadingImage = state.isUploadingImage
        error = state.error
        aliases = Array(state.aliases)
        mergeQuery = state.mergeQuery
        renameCollisionCandidate = state.renameCollisionCandidate.map(MergeCandidate.init)
    }

    private func applyNav(_ action: ContributorEditNavAction) {
        switch onEnum(of: action) {
        case .navigateBack, .saveSuccess: didFinish = true
        case .navigateToMerged(let merged):
            // A merge can soft-delete the contributor being edited (the rename-collision path), so
            // dismissing would return to a detail page for something that no longer exists. Surface
            // the survivor so the presenting detail view can re-target itself in place.
            mergedIntoContributorId = merged.contributorId.value
            didFinish = true
        case .unknown: Log.error("Unexpected ContributorEditNavAction case")
        }
    }
}
