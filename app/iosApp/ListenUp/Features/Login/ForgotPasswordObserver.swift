import Foundation
import Shared

/// Native phase mirror of the shared `ForgotPasswordUiState` — a value type so SwiftUI diffs
/// never re-read bridged Kotlin properties (iOS rule 8).
enum ForgotPasswordPhase: Equatable {
    case enterEmail
    case submitting
    case awaitingApproval
    /// Approved — collecting the out-of-band code plus the new password. `error` is the
    /// wrong-code feedback the ViewModel retained; `attemptsRemaining` its budget, when known.
    case enterCode(attemptsRemaining: Int?, error: String?)
    case denied
    case complete
    case error(message: String)
}

/// Observes `ForgotPasswordViewModel.state`, flattening each sealed arm into
/// [ForgotPasswordPhase]. Thin over `FlowBridge`, mirroring `LoginViewModelWrapper`.
@Observable
@MainActor
final class ForgotPasswordObserver {
    private(set) var phase: ForgotPasswordPhase = .enterEmail

    private let viewModel: ForgotPasswordViewModel
    private let bridge = FlowBridge()

    init(viewModel: ForgotPasswordViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    // Isolated deinit (SE-0371): no ViewModelStore on iOS calls onCleared, so this wrapper must
    // close() the VM itself (#1192) — else its stream/poll watch jobs orphan and run forever
    // (the ForgotPasswordViewModel KDoc calls this contract out explicitly).
    isolated deinit {
        bridge.cancelAll()
        viewModel.close()
    }

    // MARK: - Actions

    func requestReset(email: String) {
        viewModel.requestReset(email: email)
    }

    func checkStatus() {
        viewModel.checkStatus()
    }

    func completeReset(code: String, newPassword: String) {
        viewModel.completeReset(code: code, newPassword: newPassword)
    }

    // MARK: - State mapping

    private func apply(_ state: ForgotPasswordUiState) {
        switch onEnum(of: state) {
        case .enterEmail:
            phase = .enterEmail
        case .submitting:
            phase = .submitting
        case .awaitingApproval:
            phase = .awaitingApproval
        case .enterCode(let enterCode):
            phase = .enterCode(
                attemptsRemaining: enterCode.attemptsRemaining.map { Int($0) },
                error: enterCode.error
            )
        case .denied:
            phase = .denied
        case .complete:
            phase = .complete
        case .error(let error):
            phase = .error(message: error.message)
        case .unknown:
            Log.error("Unexpected ForgotPasswordUiState case")
            phase = .error(message: String(localized: "common.something_went_wrong"))
        }
    }
}
