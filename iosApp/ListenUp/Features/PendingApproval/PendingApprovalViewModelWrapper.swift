import Foundation
import Shared

/// Observes `PendingApprovalViewModel`'s `state` flow, flattening `PendingApprovalUiState`
/// into SwiftUI-native state. Thin over `FlowBridge`.
@Observable
@MainActor
final class PendingApprovalViewModelWrapper {

    // MARK: - State

    enum Phase: Equatable {
        case waiting
        case approved
        case denied(String)
    }

    private(set) var phase: Phase = .waiting
    let email: String

    // MARK: - Dependencies

    private let viewModel: PendingApprovalViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: PendingApprovalViewModel) {
        self.viewModel = viewModel
        self.email = viewModel.email
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    // Isolated deinit (SE-0371, Swift 6.2): runs hopped onto the main actor, so the
    // non-Sendable Kotlin `viewModel` may be safely touched here during teardown.
    isolated deinit {
        bridge.cancelAll()   // cancelAll() is nonisolated-safe; see FlowBridge.
        // The Kotlin VM is resolved from a bare Koin `factory` — there is no ViewModelStore on iOS
        // to call `onCleared()` for us, so this wrapper's teardown must do it explicitly, or the
        // VM's stream/poll jobs orphan and run forever (the reconnect-flood bug this fixes).
        viewModel.close()
    }

    // MARK: - Actions

    func cancel() {
        viewModel.cancelRegistration()
    }

    func acknowledge() {
        viewModel.acknowledgeApproval()
    }

    /// Manually re-check approval status — re-opens the registration-status watch (never-stranded fallback).
    func checkStatus() {
        viewModel.checkStatus()
    }

    // MARK: - State mapping

    private func apply(_ state: PendingApprovalUiState) {
        switch onEnum(of: state) {
        case .waiting:
            phase = .waiting
        case .approved:
            phase = .approved
        case .denied(let denied):
            phase = .denied(denied.message)
        case .unknown:
            Log.error("Unexpected PendingApprovalUiState case")
            phase = .waiting
        }
    }
}
