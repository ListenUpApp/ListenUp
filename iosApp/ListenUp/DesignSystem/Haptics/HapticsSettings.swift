import SwiftUI
import Shared

/// App-lifetime source of truth for whether haptics are enabled, bridged from the shared
/// `SettingsViewModel`. Injected at the root via `.environment(...)` and read by `.haptic(_:trigger:)`.
@Observable
@MainActor
final class HapticsSettings {
    private(set) var isEnabled: Bool = true

    private let viewModel: SettingsViewModel
    private let bridge = FlowBridge()

    init(viewModel: SettingsViewModel = Dependencies.shared.createSettingsViewModel()) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] state in
            self?.isEnabled = state.hapticFeedbackEnabled
        }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.
}
