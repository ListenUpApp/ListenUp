import SwiftUI
@preconcurrency import Shared

/// The phase a first-run (or returning) admin's library is in, flattened from the KMP
/// ``LibraryReadiness`` sealed type for a SwiftUI `switch`. Mirrors the Android nav gate:
/// a setup question (`needsSetup`/`checkFailed`) always outranks population, which outranks
/// `ready`.
enum LibraryReadinessPhase: Equatable {
    case checking
    case needsSetup
    case populating
    case ready
    case checkFailed
}

/// Observes ``AppStartupViewModel/readiness`` — the single authoritative answer to "what
/// should the authenticated app show right now?" — and exposes it as SwiftUI-native state.
/// Thin over ``FlowBridge``. Drives the post-auth onboarding gate at the app root:
/// `needsSetup` routes into the library-setup wizard; everything else mounts the main app.
@Observable
@MainActor
final class LibraryReadinessObserver {
    private(set) var phase: LibraryReadinessPhase = .checking

    private let appStartupViewModel: AppStartupViewModel
    private let bridge = FlowBridge()

    init(appStartupViewModel: AppStartupViewModel = KoinHelper.shared.getAppStartupViewModel()) {
        self.appStartupViewModel = appStartupViewModel
        bridge.bind(appStartupViewModel.readiness) { [weak self] in self?.apply($0) }
    }

    /// Stop observing. Call on teardown.
    func stopObserving() {
        bridge.cancelAll()
    }

    /// Clear the needs-setup latch once the admin finishes the create-library wizard,
    /// flipping readiness to `ready` so the root mounts the main app.
    func onLibrarySetupComplete() {
        appStartupViewModel.onLibrarySetupComplete()
    }

    // MARK: - Mapping

    private func apply(_ readiness: LibraryReadiness) {
        switch onEnum(of: readiness) {
        case .checking:
            phase = .checking
        case .needsSetup:
            phase = .needsSetup
        case .populating:
            phase = .populating
        case .ready:
            phase = .ready
        case .checkFailed:
            phase = .checkFailed
        }
    }
}
