import Foundation
@preconcurrency import Shared

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

    func stopObserving() {
        bridge.cancelAll()
    }

    // MARK: - Actions

    func cancel() {
        viewModel.cancelRegistration()
    }

    func acknowledge() {
        viewModel.acknowledgeApproval()
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
        }
    }
}
