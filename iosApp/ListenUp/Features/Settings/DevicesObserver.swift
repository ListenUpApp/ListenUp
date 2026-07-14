import SwiftUI
import Shared

/// The render phase for the Devices screen, flattened from `DevicesUiState`.
enum DevicesPhase {
    case loading
    case ready(devices: [DeviceRow], signingOut: Set<String>)
    case error(String)
}

/// Observes `DevicesViewModel`, flattening `DevicesUiState` into flat `@Observable`
/// properties the SwiftUI Devices screen binds to. Mirrors `TagDetailObserver`.
@Observable
@MainActor
final class DevicesObserver {
    // MARK: - State

    private(set) var phase: DevicesPhase = .loading

    // MARK: - Dependencies

    private let viewModel: DevicesViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: DevicesViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.uiState) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func retry() { viewModel.retry() }

    func revokeDevice(_ sessionId: String) { viewModel.revokeDevice(sessionId: sessionId) }

    func signOutEverywhere(onDone: @escaping () -> Void) {
        viewModel.signOutEverywhere(onDone: onDone)
    }

    // MARK: - State mapping

    private func apply(_ state: DevicesUiState) {
        switch onEnum(of: state) {
        case .loading:
            phase = .loading
        case .ready(let ready):
            let devices = Array(ready.devices)
            // The Kotlin Set<String> arrives as a bridged Kotlin set, not a Swift Set;
            // map through String(describing:) to produce a Swift-native Set<String>.
            let signingOut = Set(ready.signingOut.map { String(describing: $0) })
            phase = .ready(devices: devices, signingOut: signingOut)
        case .error(let errorState):
            phase = .error(errorState.error.message)
        case .unknown:
            Log.error("Unexpected DevicesUiState case")
            phase = .error(String(localized: "common.something_went_wrong"))
        }
    }
}
