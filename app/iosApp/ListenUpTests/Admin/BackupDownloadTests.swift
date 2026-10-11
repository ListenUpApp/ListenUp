import Foundation
import Testing
@testable import ListenUp

/// The pure pieces of the backup download: where the archive is staged on disk, and the small state
/// machine behind the row's busy indicator and the "Save to Files" hand-off.
@Suite("Backup download staging")
struct BackupDownloadStagingTests {
    private let directory = URL(filePath: "/tmp/listenup-tests", directoryHint: .isDirectory)
    private let token = UUID(uuidString: "00000000-0000-0000-0000-000000000001")!

    @Test func stagesUnderAPerDownloadFolderWithTheArchivesOwnName() {
        let url = BackupDownloadStaging.stagingURL(forBackupId: "backup-2026.listenup.zip", in: directory, token: token)

        #expect(url.lastPathComponent == "backup-2026.listenup.zip")
        #expect(url.deletingLastPathComponent().lastPathComponent == token.uuidString)
        #expect(url.deletingLastPathComponent().deletingLastPathComponent().path == directory.path)
    }

    @Test func anIdWithoutAnExtensionGetsZip() {
        let url = BackupDownloadStaging.stagingURL(forBackupId: "backup-7", in: directory, token: token)

        #expect(url.lastPathComponent == "backup-7.zip")
    }

    @Test func aPathLikeIdCannotEscapeTheStagingFolder() {
        let url = BackupDownloadStaging.stagingURL(forBackupId: "../../etc/passwd", in: directory, token: token)

        #expect(url.lastPathComponent == "passwd.zip")
        #expect(url.deletingLastPathComponent().lastPathComponent == token.uuidString)
    }

    @Test func anEmptyOrDotIdFallsBackToBackupZip() {
        for id in ["", ".", ".."] {
            let url = BackupDownloadStaging.stagingURL(forBackupId: id, in: directory, token: token)
            #expect(url.lastPathComponent == "backup.zip")
        }
    }

    @Test func discardRemovesTheFileAndItsFolder() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "backup-staging-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let url = BackupDownloadStaging.stagingURL(forBackupId: "b.zip", in: root, token: token)
        try BackupDownloadStaging.prepare(url)
        try Data("x".utf8).write(to: url)

        BackupDownloadStaging.discard(url)

        #expect(!FileManager.default.fileExists(atPath: url.path))
        #expect(!FileManager.default.fileExists(atPath: url.deletingLastPathComponent().path))
    }

    @Test func discardOfAFileAlreadyMovedAwayIsHarmless() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "backup-staging-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let url = BackupDownloadStaging.stagingURL(forBackupId: "b.zip", in: root, token: token)

        BackupDownloadStaging.discard(url)

        #expect(!FileManager.default.fileExists(atPath: url.path))
    }
}

@Suite("Backup download tracker")
struct BackupDownloadTrackerTests {
    private let url = URL(filePath: "/tmp/a/b.zip")

    @Test func beginMarksTheRowBusy() {
        var tracker = BackupDownloadTracker()

        let started = tracker.begin(id: "b", url: url)

        #expect(started)
        #expect(tracker.downloadingId == "b")
        #expect(tracker.ready == nil)
    }

    @Test func aSecondDownloadWhileOneRunsIsRefused() {
        var tracker = BackupDownloadTracker()
        _ = tracker.begin(id: "b", url: url)

        #expect(tracker.begin(id: "c", url: url) == false)
        #expect(tracker.downloadingId == "b")
    }

    @Test func savedMovesTheDownloadToReadyAndClearsBusy() {
        var tracker = BackupDownloadTracker()
        _ = tracker.begin(id: "b", url: url)

        tracker.saved()

        #expect(tracker.downloadingId == nil)
        #expect(tracker.ready == BackupDownloadTracker.Ready(id: "b", url: url))
    }

    /// A failed or abandoned download reaches the user through the global error surface; here it only
    /// stops being busy, and nothing is offered for saving.
    @Test func endedWithoutSavingClearsBusyAndOffersNothing() {
        var tracker = BackupDownloadTracker()
        _ = tracker.begin(id: "b", url: url)

        tracker.endedWithoutSaving()

        #expect(tracker.downloadingId == nil)
        #expect(tracker.ready == nil)
    }

    @Test func finishingTheExportClearsReadyAndReturnsTheFileToDiscard() {
        var tracker = BackupDownloadTracker()
        _ = tracker.begin(id: "b", url: url)
        tracker.saved()

        let leftover = tracker.finishExport()

        #expect(leftover == url)
        #expect(tracker.ready == nil)
        #expect(tracker.finishExport() == nil)
    }

    @Test func aNewDownloadIsRefusedWhileTheLastOneAwaitsItsDestination() {
        var tracker = BackupDownloadTracker()
        _ = tracker.begin(id: "b", url: url)
        tracker.saved()

        #expect(tracker.begin(id: "c", url: url) == false)
    }
}
