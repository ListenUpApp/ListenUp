import Testing
import Foundation
import Shared
@testable import ListenUp

/// The value-type mappings the backup/restore observers perform at their boundary — the guard
/// against feeding Swift-Export-bridged Kotlin objects into a `ForEach` (iosApp rule 8) and against
/// a `BackupEvent`/UiState case being routed to the wrong label. Mirrors `AdminSettingsObserverTests`
/// and `ABSImportHubObserver`'s testable statics.

// MARK: - Backup row mapping

@Suite("Backup row mapping")
struct BackupRowModelTests {
    @Test func mapsIdSizeAndCreatedAt() {
        let epochMillis: Int64 = 1_712_000_000_000
        let info = BackupInfo(id: "backup-1", size: 2048, createdAt: Timestamp(epochMillis: epochMillis))
        let model = BackupRowModel(from: info)

        #expect(model.id == "backup-1")
        // createdAt is epoch-millis → seconds; assert the exact interval survives the conversion.
        #expect(model.createdAt.timeIntervalSince1970 == Double(epochMillis) / 1000)
        // sizeFormatted is the domain model's pre-formatted passthrough.
        #expect(model.sizeFormatted == info.sizeFormatted)
    }
}

// MARK: - Backups phase mapping

@Suite("Backups phase mapping")
struct BackupsPhaseTests {
    @Test func loadingMapsToLoading() {
        #expect(AdminBackupsObserver.phase(from: AdminBackupUiStateLoading.shared) == .loading)
    }

    @Test func readyCarriesBackupsAndDeleteConfirmTarget() {
        let info = BackupInfo(id: "b1", size: 1024, createdAt: Timestamp(epochMillis: 1_712_000_000_000))
        let ready = AdminBackupUiStateReady(
            backups: [info],
            isCreating: true,
            isDeleting: false,
            error: nil,
            deleteConfirmBackup: info
        )
        guard case .ready(let model) = AdminBackupsObserver.phase(from: ready) else {
            Issue.record("expected .ready")
            return
        }
        #expect(model.backups.count == 1)
        #expect(model.backups.first?.id == "b1")
        #expect(model.isCreating == true)
        #expect(model.error == nil)
        #expect(model.deleteConfirmBackup?.id == "b1")
    }

    @Test func readyErrorIsSurfaced() {
        let appError = TransportErrorNetworkUnavailable(correlationId: nil, debugInfo: nil)
        let ready = AdminBackupUiStateReady(
            backups: [],
            isCreating: false,
            isDeleting: false,
            error: appError,
            deleteConfirmBackup: nil
        )
        guard case .ready(let model) = AdminBackupsObserver.phase(from: ready) else {
            Issue.record("expected .ready")
            return
        }
        #expect(model.error == appError.message)
    }

    @Test func errorStateMapsToError() {
        let appError = TransportErrorNetworkUnavailable(correlationId: nil, debugInfo: nil)
        #expect(AdminBackupsObserver.phase(from: AdminBackupUiStateError(error: appError)) == .error(message: appError.message))
    }
}

// MARK: - Restore phase mapping

@Suite("Restore phase mapping")
struct RestorePhaseTests {
    @Test func idleMapsToIdleWithError() {
        let appError = TransportErrorNetworkUnavailable(correlationId: nil, debugInfo: nil)
        #expect(RestoreBackupObserver.phase(from: RestoreBackupUiStateIdle(error: nil)) == .idle(error: nil))
        #expect(RestoreBackupObserver.phase(from: RestoreBackupUiStateIdle(error: appError)) == .idle(error: appError.message))
    }

    @Test func confirmingAndRestoringMap() {
        #expect(RestoreBackupObserver.phase(from: RestoreBackupUiStateConfirming.shared) == .confirming)
        #expect(RestoreBackupObserver.phase(from: RestoreBackupUiStateRestoring.shared) == .restoring)
    }

    @Test func completedMapsResultFields() {
        let result = RestoreResult(
            restoredFrom: BackupId(value: "backup-7"),
            includedImages: true,
            schemaMigratedFrom: "5",
            schemaMigratedTo: "7"
        )
        guard case .completed(let model) = RestoreBackupObserver.phase(from: RestoreBackupUiStateCompleted(result: result)) else {
            Issue.record("expected .completed")
            return
        }
        #expect(model.restoredFrom == "backup-7")
        #expect(model.schemaMigratedFrom == "5")
        #expect(model.schemaMigratedTo == "7")
        #expect(model.includedImages == true)
    }
}

// MARK: - Restore status labels

@Suite("Restore status labels")
struct RestoreStatusLabelTests {
    @Test func nilMapsToDefault() {
        #expect(RestoreBackupObserver.statusLabel(from: nil) == String(localized: "admin.restore_status_default"))
    }

    @Test func eachEventMapsToItsLabel() {
        #expect(RestoreBackupObserver.statusLabel(from: BackupEventValidating.shared) == String(localized: "admin.restore_status_validating"))
        #expect(RestoreBackupObserver.statusLabel(from: BackupEventDraining.shared) == String(localized: "admin.restore_status_draining"))
        #expect(RestoreBackupObserver.statusLabel(from: BackupEventSwapping.shared) == String(localized: "admin.restore_status_swapping"))
        #expect(RestoreBackupObserver.statusLabel(from: BackupEventMigrating.shared) == String(localized: "admin.restore_status_migrating"))
        #expect(RestoreBackupObserver.statusLabel(from: BackupEventRestoreComplete(includedImages: true)) == String(localized: "admin.restore_status_finishing"))
        #expect(RestoreBackupObserver.statusLabel(from: BackupEventRolledBack(reason: "boom")) == String(localized: "admin.restore_status_rolling_back"))
    }

    @Test func otherEventsMapToDefault() {
        // Backup-creation events are not restore progress — they fall through to the default label.
        #expect(RestoreBackupObserver.statusLabel(from: BackupEventFinalizing.shared) == String(localized: "admin.restore_status_default"))
        #expect(RestoreBackupObserver.statusLabel(from: BackupEventDbSnapshotting.shared) == String(localized: "admin.restore_status_default"))
    }
}

// MARK: - Upload phase mapping

@Suite("Restore-from-file upload phase mapping")
struct UploadPhaseTests {
    @Test func idleMapsToIdle() {
        #expect(RestoreFromFileObserver.phase(from: RestoreFromFileUiStateIdle.shared) == .idle)
    }

    @Test func uploadingCarriesFilename() {
        #expect(RestoreFromFileObserver.phase(from: RestoreFromFileUiStateUploading(filename: "library.listenup.zip")) == .uploading(filename: "library.listenup.zip"))
    }

    @Test func errorSurfacesMessage() {
        let appError = TransportErrorNetworkUnavailable(correlationId: nil, debugInfo: nil)
        #expect(RestoreFromFileObserver.phase(from: RestoreFromFileUiStateError(error: appError)) == .error(message: appError.message))
    }
}
