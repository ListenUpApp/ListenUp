import Foundation
import Shared

/// Observes `AdminBackupViewModel` — flattens the sealed `AdminBackupUiState`
/// (`Loading` / `Ready` / `Error`) into a SwiftUI-native `BackupsPhase` the backups screen
/// binds to. The VM owns the create/delete overlays and the delete-confirm dialog state; this
/// observer projects them onto native value types and delegates every action back to the VM.
///
/// Row models and the phase classification live in pure, testable initializers / statics.
/// Thin over `FlowBridge`, mirroring `ABSImportHubObserver` (iosApp rule 8: no bridged Kotlin
/// object ever reaches a `ForEach`).
@Observable
@MainActor
final class AdminBackupsObserver {
    // MARK: - State

    private(set) var phase: BackupsPhase = .loading

    // MARK: - Dependencies

    private let viewModel: AdminBackupViewModel
    private let bridge = FlowBridge()

    /// The raw bridged backups, keyed by id — kept off the diff path so the
    /// delete/confirm actions (which need the Kotlin `BackupInfo`) can look one up without
    /// ever feeding a bridged object to the view.
    private var backupsById: [String: BackupInfo] = [:]

    // MARK: - Init

    init(viewModel: AdminBackupViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func reload() { viewModel.loadBackups() }

    func createBackup(includeImages: Bool) { viewModel.createBackup(includeImages: includeImages) }

    /// Ask the VM to show the destructive delete confirmation for `id`.
    func requestDelete(id: String) {
        guard let backup = backupsById[id] else { return }
        viewModel.showDeleteConfirmation(backup: backup)
    }

    func cancelDelete() { viewModel.dismissDeleteConfirmation() }

    func confirmDelete(id: String) {
        guard let backup = backupsById[id] else { return }
        viewModel.deleteBackup(backup: backup)
    }

    func clearError() { viewModel.clearError() }

    // MARK: - State mapping

    private func apply(_ state: AdminBackupUiState) {
        if case .ready(let ready) = onEnum(of: state) {
            backupsById = Dictionary(ready.backups.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        } else {
            backupsById = [:]
        }
        phase = Self.phase(from: state)
    }

    /// Pure: project the sealed `AdminBackupUiState` onto the backups screen's phase.
    /// `nonisolated` so tests can exercise it off the main actor.
    nonisolated static func phase(from state: AdminBackupUiState) -> BackupsPhase {
        switch onEnum(of: state) {
        case .loading:
            return .loading
        case .ready(let ready):
            return .ready(BackupsReadyModel(from: ready))
        case .error(let error):
            return .error(message: error.error.message)
        case .unknown:
            Log.error("Unexpected AdminBackupUiState case")
            return .error(message: String(localized: "common.something_went_wrong"))
        }
    }
}

// MARK: - Phase

/// Flattened backups-list state for a SwiftUI `switch`.
enum BackupsPhase: Equatable {
    case loading
    case ready(BackupsReadyModel)
    case error(message: String)
}

// MARK: - Ready model

/// The backups snapshot: the stored backups (newest first), the action overlays, the transient
/// mutation error surfaced as an alert, and the VM-owned delete-confirm target.
struct BackupsReadyModel: Equatable {
    let backups: [BackupRowModel]
    let isCreating: Bool
    let isDeleting: Bool
    let error: String?
    let deleteConfirmBackup: BackupRowModel?

    init(from ready: AdminBackupUiStateReady) {
        self.backups = ready.backups.map(BackupRowModel.init(from:))
        self.isCreating = ready.isCreating
        self.isDeleting = ready.isDeleting
        self.error = ready.error?.message
        self.deleteConfirmBackup = ready.deleteConfirmBackup.map(BackupRowModel.init(from:))
    }

    init(
        backups: [BackupRowModel],
        isCreating: Bool,
        isDeleting: Bool,
        error: String?,
        deleteConfirmBackup: BackupRowModel?
    ) {
        self.backups = backups
        self.isCreating = isCreating
        self.isDeleting = isDeleting
        self.error = error
        self.deleteConfirmBackup = deleteConfirmBackup
    }
}

// MARK: - Backup row model

/// One row in the backups list: the archive id (its human identifier), the created timestamp, and
/// the pre-formatted size. A native value type so SwiftUI never diffs a bridged Kotlin object.
struct BackupRowModel: Identifiable, Equatable {
    let id: String
    let createdAt: Date
    let sizeFormatted: String

    init(from info: BackupInfo) {
        self.id = info.id
        self.createdAt = Date(timeIntervalSince1970: Double(info.createdAt.epochMillis) / 1000)
        self.sizeFormatted = info.sizeFormatted
    }

    init(id: String, createdAt: Date, sizeFormatted: String) {
        self.id = id
        self.createdAt = createdAt
        self.sizeFormatted = sizeFormatted
    }
}
