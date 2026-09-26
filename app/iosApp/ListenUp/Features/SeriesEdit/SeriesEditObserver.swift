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
    /// The series description — stored as `seriesDescription` and read from Kotlin's
    /// `descriptionText` alias: Swift Export never exports a member named `description`.
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

    // MARK: - Merge (#1061)

    private(set) var bookCount: Int = 0
    private(set) var mergeInProgress: Bool = false
    private(set) var mergeQuery: String = ""
    /// Recomputed by the VM only while the merge picker is open, and capped there.
    private(set) var mergeCandidates: [MergeCandidate] = []
    /// The merges folded into this series, each undoable — the "Merged into this" section.
    private(set) var mergeHistory: MergeHistoryModel = .loading

    private let viewModel: SeriesEditViewModel
    private let bridge = FlowBridge()

    init(viewModel: SeriesEditViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.navActions) { [weak self] in self?.applyNav($0) }
        bridge.bind(viewModel.mergeCandidates) { [weak self] candidates in
            self?.mergeCandidates = candidates.map(MergeCandidate.init(series:))
        }
        bridge.bind(viewModel.mergeHistory) { [weak self] in self?.mergeHistory = MergeHistoryModel.from($0) }
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

    /// Tells the VM the merge picker is open — candidate computation runs only while it is.
    func onMergeDialogOpened() { viewModel.onEvent(event: SeriesEditUiEventMergeDialogOpened.shared) }
    func onMergeDialogDismissed() { viewModel.onEvent(event: SeriesEditUiEventMergeDialogDismissed.shared) }
    func onMergeQueryChange(_ value: String) { viewModel.onMergeQueryChange(query: value) }
    func onMergeInto(_ targetId: String) {
        viewModel.onEvent(event: SeriesEditUiEventMergeInto(targetId: SeriesId(value: targetId)))
    }
    func onUndoMerge(_ receiptId: String) {
        viewModel.onEvent(event: SeriesEditUiEventUndoMerge(receiptId: MergeReceiptId(value: receiptId)))
    }
    func onRetryMergeHistory() { viewModel.onEvent(event: SeriesEditUiEventRetryMergeHistory.shared) }

    private func apply(_ state: SeriesEditUiState) {
        isLoading = state.isLoading
        name = state.name
        seriesDescription = state.descriptionText
        displayCoverPath = state.displayCoverPath
        hasChanges = state.hasChanges
        isSaving = state.isSaving
        isUploadingCover = state.isUploadingCover
        error = state.error
        bookCount = Int(state.bookCount)
        mergeInProgress = state.mergeInProgress
        mergeQuery = state.mergeQuery
    }

    private func applyNav(_ action: SeriesEditNavAction) {
        switch action.sealedType() {
        case .navigateBack: didFinish = true
        case .navigateToMerged(let mergedType):
            let merged = mergedType.value
            // A merge soft-deletes the series being edited, so dismissing returns to a detail page
            // for something that no longer exists. Android lands on the survivor instead; iOS
            // cannot yet, because this screen is a sheet whose presenter owns navigation and has no
            // way to be told where to go. The id is surfaced so a presenter can act on it — until
            // one does, iOS keeps the old dismiss behaviour rather than silently doing nothing.
            mergedIntoSeriesId = merged.seriesId.value
            didFinish = true
        }
    }
}

extension MergeCandidate {
    /// Snapshot a Kotlin `SeriesCandidate` into native values for the series merge picker.
    init(series candidate: SeriesCandidate) {
        self.init(id: candidate.id.value, name: candidate.displayName)
    }
}
