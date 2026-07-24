import Foundation
import Shared

/// Observes `RestoreFromFileViewModel` — the pick + upload step that precedes the destructive
/// restore. Flattens the sealed `RestoreFromFileUiState` into an `UploadPhase`, and drains the
/// one-shot `navigation` flow into `stagedBackupId` so the backups screen can push the
/// restore-confirmation flow for the staged archive.
///
/// Thin over `FlowBridge`; the actual bytes are read off the main thread by the shared
/// `ImportFileSourceBridge` and handed to the VM as a non-`Sendable` `FileSource`.
@Observable
@MainActor
final class RestoreFromFileObserver {
    // MARK: - State

    private(set) var phase: UploadPhase = .idle

    /// One-shot: the staged backup id emitted after a successful upload. The view consumes and
    /// clears it (`nil`) once the restore-confirm push is enqueued.
    var stagedBackupId: String?

    // MARK: - Dependencies

    private let viewModel: RestoreFromFileViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: RestoreFromFileViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.navigation) { [weak self] id in self?.stagedBackupId = id.value }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func onFilePicked(fileSource: FileSource) { viewModel.onFilePicked(fileSource: fileSource) }

    func reset() { viewModel.reset() }

    // MARK: - State mapping

    private func apply(_ state: RestoreFromFileUiState) {
        phase = Self.phase(from: state)
    }

    /// Pure: project the sealed `RestoreFromFileUiState` onto the upload phase.
    /// `nonisolated` so tests can exercise it off the main actor.
    nonisolated static func phase(from state: RestoreFromFileUiState) -> UploadPhase {
        switch onEnum(of: state) {
        case .idle:
            return .idle
        case .uploading(let uploading):
            return .uploading(filename: uploading.filename)
        case .error(let error):
            return .error(message: error.error.message)
        case .unknown:
            Log.error("Unexpected RestoreFromFileUiState case")
            return .error(message: String(localized: "common.something_went_wrong"))
        }
    }
}

// MARK: - Upload phase

/// Flattened restore-from-file upload state for a SwiftUI `switch`.
enum UploadPhase: Equatable {
    case idle
    case uploading(filename: String)
    case error(message: String)
}
