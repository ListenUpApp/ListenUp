import SwiftUI
@preconcurrency import Shared

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

    deinit {
        // Held in SwiftUI `@State` on a `@MainActor` view, so dealloc is main-thread.
        MainActor.assumeIsolated { bridge.cancelAll() }
    }

    func stopObserving() { bridge.cancelAll() }

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
            // Kotlin Set<String> bridges via SKIE as a KotlinMutableSet;
            // map through String(describing:) to produce a Swift-native Set<String>.
            let signingOut = Set(ready.signingOut.map { String(describing: $0) })
            phase = .ready(devices: devices, signingOut: signingOut)
        case .error(let errorState):
            phase = .error(errorState.message)
        }
    }
}
