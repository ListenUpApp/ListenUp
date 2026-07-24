import Foundation
import Shared

/// Observes `RestoreBackupViewModel` — the destructive restore of one already-staged backup.
/// Flattens the sealed `RestoreBackupUiState` into a `RestorePhase`, and maps the live
/// `progress` (`BackupEvent`) stream into a short status label for the restoring spinner.
///
/// The VM owns the confirm state machine (`requestRestore` → `confirmRestore`/`cancelRestore`)
/// and runs the post-restore full resync itself, so this observer only forwards actions and
/// projects state. Thin over `FlowBridge` (iosApp rule 8).
@Observable
@MainActor
final class RestoreBackupObserver {
    // MARK: - State

    private(set) var phase: RestorePhase = .idle(error: nil)
    private(set) var statusLabel: String = String(localized: "admin.restore_status_default")

    // MARK: - Dependencies

    private let viewModel: RestoreBackupViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: RestoreBackupViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.progress) { [weak self] event in
            self?.statusLabel = Self.statusLabel(from: event)
        }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func requestRestore() { viewModel.requestRestore() }
    func cancelRestore() { viewModel.cancelRestore() }
    func confirmRestore() { viewModel.confirmRestore() }

    // MARK: - State mapping

    private func apply(_ state: RestoreBackupUiState) {
        phase = Self.phase(from: state)
    }

    /// Pure: project the sealed `RestoreBackupUiState` onto the restore phase.
    /// `nonisolated` so tests can exercise it off the main actor.
    nonisolated static func phase(from state: RestoreBackupUiState) -> RestorePhase {
        switch onEnum(of: state) {
        case .idle(let idle):
            return .idle(error: idle.error?.message)
        case .confirming:
            return .confirming
        case .restoring:
            return .restoring
        case .completed(let completed):
            return .completed(RestoreCompletedModel(from: completed.result))
        case .unknown:
            Log.error("Unexpected RestoreBackupUiState case")
            return .idle(error: String(localized: "common.something_went_wrong"))
        }
    }

    /// Pure: map the live restore progress event into a short status label. Mirrors the Android
    /// `restoreStatusLabel` mapping (RestoreBackupScreen.kt), routed through the `admin.restore_status_*`
    /// keys. `nonisolated` so tests can exercise it off the main actor.
    nonisolated static func statusLabel(from event: BackupEvent?) -> String {
        guard let event else { return String(localized: "admin.restore_status_default") }
        switch onEnum(of: event) {
        case .validating:
            return String(localized: "admin.restore_status_validating")
        case .draining:
            return String(localized: "admin.restore_status_draining")
        case .swapping:
            return String(localized: "admin.restore_status_swapping")
        case .migrating:
            return String(localized: "admin.restore_status_migrating")
        case .restoreComplete:
            return String(localized: "admin.restore_status_finishing")
        case .rolledBack:
            return String(localized: "admin.restore_status_rolling_back")
        default:
            return String(localized: "admin.restore_status_default")
        }
    }
}

// MARK: - Restore phase

/// Flattened restore state for a SwiftUI `switch`.
enum RestorePhase: Equatable {
    case idle(error: String?)
    case confirming
    case restoring
    case completed(RestoreCompletedModel)
}

// MARK: - Completed model

/// The outcome shown on the completed screen: the source archive, the schema migration span, and
/// whether cover images / avatars rode along. A native value type at the observer boundary.
struct RestoreCompletedModel: Equatable {
    let restoredFrom: String
    let schemaMigratedFrom: String
    let schemaMigratedTo: String
    let includedImages: Bool

    init(from result: RestoreResult) {
        self.restoredFrom = result.restoredFrom.value
        self.schemaMigratedFrom = result.schemaMigratedFrom
        self.schemaMigratedTo = result.schemaMigratedTo
        self.includedImages = result.includedImages
    }

    init(restoredFrom: String, schemaMigratedFrom: String, schemaMigratedTo: String, includedImages: Bool) {
        self.restoredFrom = restoredFrom
        self.schemaMigratedFrom = schemaMigratedFrom
        self.schemaMigratedTo = schemaMigratedTo
        self.includedImages = includedImages
    }
}
