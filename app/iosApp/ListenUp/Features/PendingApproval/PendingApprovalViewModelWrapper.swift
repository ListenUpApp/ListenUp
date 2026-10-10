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
    /// `true` once this device holds a registration watch (#1068) — the screen may then
    /// honestly promise "we'll notify you when you're approved".
    private(set) var notifyPromise = false
    let email: String

    // MARK: - Dependencies

    private let viewModel: PendingApprovalViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: PendingApprovalViewModel) {
        self.viewModel = viewModel
        bridge.own(viewModel)
        self.email = viewModel.email
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.notifyPromise) { [weak self] promised in
            self?.notifyPromise = promised
        }
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
        switch state.sealedType() {
        case .waiting:
            phase = .waiting
        case .approved:
            phase = .approved
        case .denied(let deniedType):
            let denied = deniedType.value
            phase = .denied(denied.message)
        }
    }
}
