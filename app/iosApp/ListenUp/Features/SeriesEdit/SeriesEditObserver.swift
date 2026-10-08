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
    /// May merge this series and see and undo its merges (Curate library).
    private(set) var canCurateLibrary: Bool = false
    private(set) var mergeQuery: String = ""
    /// Recomputed by the VM only while the merge picker is open, and capped there.
    private(set) var mergeCandidates: [MergeCandidate] = []
    /// The merges folded into this series, each undoable — the "Merged into this" section.
    private(set) var mergeHistory: MergeHistoryModel = .loading

    // MARK: - Place in library

    /// The parent's name; nil at the top level.
    private(set) var parentName: String?
    private(set) var isTopLevel: Bool = true
    /// The series' own sub-series, in sibling order — as the VM last said, or as the reader just
    /// dropped them while that order is on its way to the server.
    private(set) var childSeries: [EditableSubSeries] = []
    /// True while a hierarchy change is on its way to the server.
    private(set) var hierarchyBusy: Bool = false
    /// Hierarchy changes need the server; offline they are disabled, and the screen says why.
    private(set) var isOnline: Bool = true
    private(set) var parentPickerVisible: Bool = false
    private(set) var parentQuery: String = ""
    private(set) var parentRows: [ParentPickerItem] = []
    /// The "New parent series" dialog, while it is open.
    private(set) var newParent: NewSeriesDraftItem?
    /// The "Add sub-series" sheet.
    private(set) var addSubSeries = AddSubSeriesSheetModel()

    /// The order the VM last emitted, so a re-emission that only flips `hierarchyBusy` doesn't snap a
    /// just-dropped list back before sync delivers the new order.
    private var lastServerChildIds: [String] = []

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
        bridge.bind(viewModel.parentPickerRows) { [weak self] rows in
            self?.parentRows = rows.map(ParentPickerItem.init)
        }
        bridge.bind(viewModel.addSubSeries) { [weak self] state in
            guard let self else { return }
            addSubSeries = AddSubSeriesSheetModel(state)
            // A refusal already reached the reader through the shared error bus (GlobalErrorObserver's
            // alert); presenting it again would say it twice (iosApp rule 10).
            if AddSubSeriesSheetModel.hasError(state) { sendAddSubSeries(.errorDismissed) }
        }
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

    // MARK: - Hierarchy actions (each goes to the server at once; nothing waits for Save)

    func openParentPicker() { viewModel.onEvent(event: SeriesEditUiEventParentPickerOpened.shared) }
    func dismissParentPicker() { viewModel.onEvent(event: SeriesEditUiEventParentPickerDismissed.shared) }
    func onParentQueryChange(_ value: String) {
        viewModel.onEvent(event: SeriesEditUiEventParentQueryChanged(query: value))
    }
    func toggleParentNode(_ id: String) {
        viewModel.onEvent(event: SeriesEditUiEventParentPickerNodeToggled(seriesId: id))
    }
    func chooseParent(_ id: String) { viewModel.onEvent(event: SeriesEditUiEventParentSelected(parentId: id)) }
    func moveToTopLevel() { viewModel.onEvent(event: SeriesEditUiEventParentCleared.shared) }
    func startNewParent() { viewModel.onEvent(event: SeriesEditUiEventNewParentStarted.shared) }
    func onNewParentNameChange(_ value: String) {
        viewModel.onEvent(event: SeriesEditUiEventNewParentNameChanged(name: value))
    }
    func dismissNewParent() { viewModel.onEvent(event: SeriesEditUiEventNewParentDismissed.shared) }
    func confirmNewParent() { viewModel.onEvent(event: SeriesEditUiEventNewParentConfirmed.shared) }

    /// "Move into it instead": the name typed for a new parent already exists, so move into that one.
    func moveIntoExisting(_ id: String) {
        chooseParent(id)
        dismissNewParent()
    }

    /// A reorder dropped: shows the new order at once and sends it whole — one
    /// `ChildSeriesReordered` per drop, never one per step. Returns the sentence VoiceOver announces
    /// ("Mistborn Era 1 moved to position 2 of 2"), or nil when nothing moved.
    @discardableResult
    func reorderChildren(to orderedIds: [String], moved movedId: String) -> String? {
        let byId = Dictionary(uniqueKeysWithValues: childSeries.map { ($0.id, $0) })
        guard orderedIds != childSeries.map(\.id), Set(orderedIds) == Set(byId.keys) else { return nil }
        childSeries = orderedIds.compactMap { byId[$0] }
        viewModel.onEvent(event: SeriesEditUiEventChildSeriesReordered(orderedChildIds: orderedIds))
        guard let series = byId[movedId], let index = orderedIds.firstIndex(of: movedId) else { return nil }
        return SeriesHierarchyText.moved(name: series.name, position: index + 1, total: orderedIds.count)
    }

    func sendAddSubSeries(_ action: AddSubSeriesAction) {
        viewModel.onAddSubSeriesEvent(event: action.kotlinEvent)
    }

    private func applyPlacement(_ state: SeriesEditUiState) {
        parentName = state.parentName
        isTopLevel = state.parentId == nil
        hierarchyBusy = state.hierarchyBusy
        isOnline = state.isOnline
        parentPickerVisible = state.parentPickerVisible
        parentQuery = state.parentQuery
        newParent = state.newParent.map(NewSeriesDraftItem.init)
        let children = state.childSeries.map(EditableSubSeries.init)
        let serverIds = children.map(\.id)
        // Take the VM's list when it changed, or when a refused change left the drop unapplied.
        if serverIds != lastServerChildIds || (state.error != nil && !state.hierarchyBusy) {
            childSeries = children
        }
        lastServerChildIds = serverIds
    }

    private func apply(_ state: SeriesEditUiState) {
        applyPlacement(state)
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
        canCurateLibrary = state.canCurateLibrary
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
