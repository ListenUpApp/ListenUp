import SwiftUI
import Shared

/// The render phase for the Devices screen, flattened from `DevicesUiState`.
enum DevicesPhase {
    case loading
    case ready(devices: [DeviceRowModel], signingOut: Set<String>)
    case error(String)
}

/// A native snapshot of one signed-in device. The Kotlin `DeviceRow` is mapped here, at the observer
/// boundary, so the Devices `List` diffs Swift values instead of re-reading bridged properties on
/// every pass (rule 8).
struct DeviceRowModel: Identifiable, Equatable {
    let sessionId: String
    let displayName: String
    /// "iOS 17.2 · ListenUp 1.0.0", or empty.
    let secondary: String
    let lastUsedAtMs: Int64
    let isCurrent: Bool

    var id: String { sessionId }

    init(sessionId: String, displayName: String, secondary: String, lastUsedAtMs: Int64, isCurrent: Bool) {
        self.sessionId = sessionId
        self.displayName = displayName
        self.secondary = secondary
        self.lastUsedAtMs = lastUsedAtMs
        self.isCurrent = isCurrent
    }

    /// Reads each bridged property once.
    init(_ row: DeviceRow) {
        self.init(
            sessionId: row.sessionId,
            displayName: row.displayName,
            secondary: row.secondary,
            lastUsedAtMs: row.lastUsedAt,
            isCurrent: row.isCurrent
        )
    }
}

/// Observes `DevicesViewModel`, flattening `DevicesUiState` into flat `@Observable`
/// properties the SwiftUI Devices screen binds to. Mirrors `FacetBooksObserver`.
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

    /// Signs out every device except this one, which stays signed in; the list then re-fetches.
    func signOutOtherDevices() { viewModel.signOutOtherDevices() }

    // MARK: - State mapping

    private func apply(_ state: DevicesUiState) {
        switch state.sealedType() {
        case .loading:
            phase = .loading
        case .ready(let readyType):
            let ready = readyType.value
            let devices = ready.devices.map(DeviceRowModel.init)
            // The Kotlin Set<String> arrives as a bridged Kotlin set, not a Swift Set;
            // map through String(describing:) to produce a Swift-native Set<String>.
            let signingOut = Set(ready.signingOut.map { String(describing: $0) })
            phase = .ready(devices: devices, signingOut: signingOut)
        case .error(let errorStateType):
            let errorState = errorStateType.value
            phase = .error(errorState.error.message)
        }
    }
}
