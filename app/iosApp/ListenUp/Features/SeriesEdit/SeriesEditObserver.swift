import SwiftUI
import Shared

/// Observes `SeriesEditViewModel`, flattening `SeriesEditUiState` into `@Observable`
/// properties and dispatching edits as `SeriesEditUiEvent`s. `NavigateBack` flips
/// `didFinish` so the sheet dismisses.
@Observable
@MainActor
final class SeriesEditObserver {
    private(set) var isLoading: Bool = true
    private(set) var name: String = ""
    /// The series description — stored as `seriesDescription` because Swift Export renames the
    /// Kotlin `description` property to `description_` (dodging the Swift `description` clash).
    private(set) var seriesDescription: String = ""
    private(set) var displayCoverPath: String?
    private(set) var hasChanges: Bool = false
    private(set) var isSaving: Bool = false
    private(set) var isUploadingCover: Bool = false
    private(set) var error: String?
    private(set) var didFinish: Bool = false
    /// Set alongside `didFinish` when a merge committed: the id of the series that survived it.
    /// Non-nil means the series this screen was editing has been deleted.
    private(set) var mergedIntoSeriesId: String?

    private let viewModel: SeriesEditViewModel
    private let bridge = FlowBridge()

    init(viewModel: SeriesEditViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.navActions) { [weak self] in self?.applyNav($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    func loadSeries(seriesId: String) { viewModel.loadSeries(seriesId: seriesId) }

    func onNameChanged(_ value: String) {
        viewModel.onEvent(event: SeriesEditUiEventNameChanged(name: value))
    }

    func onDescriptionChanged(_ value: String) {
        viewModel.onEvent(event: SeriesEditUiEventDescriptionChanged(description: value))
    }

    func onCoverSelected(_ data: Data) {
        viewModel.onEvent(event: SeriesEditUiEventCoverSelected(
            imageData: data.toKotlinByteArray(),
            filename: "cover.jpg"
        ))
    }

    func onCoverRemoved() { viewModel.onEvent(event: SeriesEditUiEventCoverRemoved.shared) }
    func onSave() { viewModel.onEvent(event: SeriesEditUiEventSaveClicked.shared) }
    func onCancel() { viewModel.onEvent(event: SeriesEditUiEventCancelClicked.shared) }
    func onDismissError() { viewModel.onEvent(event: SeriesEditUiEventErrorDismissed.shared) }

    private func apply(_ state: SeriesEditUiState) {
        isLoading = state.isLoading
        name = state.name
        seriesDescription = state.description_
        displayCoverPath = state.displayCoverPath
        hasChanges = state.hasChanges
        isSaving = state.isSaving
        isUploadingCover = state.isUploadingCover
        error = state.error
    }

    private func applyNav(_ action: SeriesEditNavAction) {
        switch onEnum(of: action) {
        case .navigateBack: didFinish = true
        case .navigateToMerged(let merged):
            // A merge soft-deletes the series being edited, so dismissing returns to a detail page
            // for something that no longer exists. Android lands on the survivor instead; iOS
            // cannot yet, because this screen is a sheet whose presenter owns navigation and has no
            // way to be told where to go. The id is surfaced so a presenter can act on it — until
            // one does, iOS keeps the old dismiss behaviour rather than silently doing nothing.
            mergedIntoSeriesId = merged.seriesId.value
            didFinish = true
        case .unknown:
            Log.error("Unexpected SeriesEditNavAction case")
        }
    }
}
