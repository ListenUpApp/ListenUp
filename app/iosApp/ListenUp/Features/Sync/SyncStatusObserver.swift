import SwiftUI
import Shared

/// A native, value-typed projection of one failed `PendingOperationUi` for SwiftUI lists.
struct PendingOperationRow: Identifiable, Equatable {
    let id: String
    let text: String
    let error: String?

    init(id: String, text: String, error: String?) {
        self.id = id
        self.text = text
        self.error = error
    }

    init(_ ui: PendingOperationUi) {
        self.id = ui.id
        self.text = ui.description_
        self.error = ui.error
    }
}

/// The compact, pure presentation of the sync outbox — what glyph (if any) the shell indicator
/// shows and whether it shows at all. Pure so the decision is unit-testable rather than tangled in
/// the toolbar view. Precedence: errors dominate, then in-flight sync, then a pending backlog; a
/// quiet, empty outbox shows nothing.
struct SyncStatusPresentation: Equatable {
    enum Icon: Equatable {
        case syncing
        case pending
        case error
    }

    let isVisible: Bool
    let icon: Icon?
    /// The badge number for the pending state; nil for syncing/error/hidden.
    let badgeCount: Int?

    static func from(isSyncing: Bool, pendingCount: Int, hasErrors: Bool) -> SyncStatusPresentation {
        if hasErrors {
            return SyncStatusPresentation(isVisible: true, icon: .error, badgeCount: nil)
        }
        if isSyncing {
            return SyncStatusPresentation(isVisible: true, icon: .syncing, badgeCount: nil)
        }
        if pendingCount > 0 {
            return SyncStatusPresentation(isVisible: true, icon: .pending, badgeCount: pendingCount)
        }
        return SyncStatusPresentation(isVisible: false, icon: nil, badgeCount: nil)
    }
}

/// Observes `SyncIndicatorViewModel`, flattening `SyncIndicatorUiState` into flat `@Observable`
/// properties the shell sync indicator binds to, and forwarding the VM's retry/dismiss events.
/// Thin over `FlowBridge`; failed operations are projected to native `PendingOperationRow` values
/// so the details list never feeds bridged Kotlin objects to a `ForEach`.
@Observable
@MainActor
final class SyncStatusObserver {
    private(set) var isSyncing: Bool = false
    private(set) var pendingCount: Int = 0
    private(set) var hasErrors: Bool = false
    private(set) var currentOperationDescription: String?
    private(set) var failedOperations: [PendingOperationRow] = []

    /// The pure indicator decision derived from the flattened counts.
    var presentation: SyncStatusPresentation {
        SyncStatusPresentation.from(isSyncing: isSyncing, pendingCount: pendingCount, hasErrors: hasErrors)
    }

    private let viewModel: SyncIndicatorViewModel
    private let bridge = FlowBridge()

    init(viewModel: SyncIndicatorViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func retry(id: String) {
        viewModel.onEvent(event: SyncIndicatorUiEventRetryOperation(operationId: id))
    }

    func dismiss(id: String) {
        viewModel.onEvent(event: SyncIndicatorUiEventDismissOperation(operationId: id))
    }

    func retryAll() {
        viewModel.onEvent(event: SyncIndicatorUiEventRetryAll.shared)
    }

    func dismissAll() {
        viewModel.onEvent(event: SyncIndicatorUiEventDismissAll.shared)
    }

    // MARK: - State mapping

    private func apply(_ state: SyncIndicatorUiState) {
        isSyncing = state.isSyncing
        pendingCount = Int(state.pendingCount)
        hasErrors = state.hasErrors
        currentOperationDescription = state.currentOperationDescription
        failedOperations = state.failedOperations.map { PendingOperationRow($0) }
    }
}
