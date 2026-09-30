import Testing
import Foundation
import Shared
@testable import ListenUp

/// What a picked folder or set of files becomes before it is sent: the structure the user chose,
/// faithfully, and nothing the server should not see. Driven against a real temporary directory,
/// because the walk is `FileManager`'s and faking it would test the fake.
@Suite("Upload selection")
struct UploadSelectionTests {
    private func makeTree(_ files: [String]) throws -> URL {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("upload-\(UUID().uuidString)")
            .appendingPathComponent("Rediscovering Christmas")
        for file in files {
            let url = root.appendingPathComponent(file)
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(), withIntermediateDirectories: true
            )
            try Data("x".utf8).write(to: url)
        }
        return root
    }

    /// The picked folder's own name leads every path — it is the strongest title signal the server
    /// gets, as on Android.
    @Test func aFolderKeepsItsShapeUnderItsOwnName() throws {
        let root = try makeTree(["01.m4b", "Disc 2/02.m4b", "cover.jpg"])

        let picks = UploadSelection.folder(root)

        #expect(picks.map(\.relPath) == [
            "Rediscovering Christmas/01.m4b",
            "Rediscovering Christmas/Disc 2/02.m4b",
            "Rediscovering Christmas/cover.jpg"
        ])
        #expect(picks.allSatisfy { FileManager.default.fileExists(atPath: $0.path) })
    }

    /// Finder litter and iCloud placeholders are hidden files; none of them is part of a book.
    @Test func hiddenFilesAreLeftBehind() throws {
        let root = try makeTree(["01.m4b", ".DS_Store", ".02.m4b.icloud"])

        #expect(UploadSelection.folder(root).map(\.relPath) == ["Rediscovering Christmas/01.m4b"])
    }

    /// Loose files arrive flat: each is its own filename, with no folder invented for it.
    @Test func looseFilesArriveFlat() throws {
        let root = try makeTree(["a/01.mp3", "b/02.mp3"])
        let urls = [root.appendingPathComponent("a/01.mp3"), root.appendingPathComponent("b/02.mp3")]

        #expect(UploadSelection.files(urls).map(\.relPath) == ["01.mp3", "02.mp3"])
    }
}

@Suite("Upload phase mapping")
struct UploadBooksPhaseTests {
    @Test func idleMapsToIdle() {
        #expect(UploadBooksObserver.phase(from: UploadBooksUiStateIdle.shared) == .idle)
    }

    /// Files are counted from one for people, from zero by the ViewModel.
    @Test func anUploadInFlightNamesItsFileAndCountsFromOne() {
        let state = UploadBooksUiStateUploading(fileIndex: 2, fileCount: 9, filename: "03.m4b", fraction: 0.25)

        #expect(UploadBooksObserver.phase(from: state) == .uploading(
            UploadProgressModel(filename: "03.m4b", fileNumber: 3, fileCount: 9, fraction: 0.25)
        ))
    }

    /// A selection that could not report its sizes has no fraction — an indeterminate bar, never
    /// one pinned at zero.
    @Test func anUnknownTotalIsIndeterminate() {
        let state = UploadBooksUiStateUploading(fileIndex: 0, fileCount: 1, filename: "a.mp3", fraction: nil)

        guard case .uploading(let progress) = UploadBooksObserver.phase(from: state) else {
            Issue.record("expected .uploading")
            return
        }
        #expect(progress.fraction == nil)
    }

    @Test func finalizingMapsToFinalizing() {
        #expect(UploadBooksObserver.phase(from: UploadBooksUiStateFinalizing.shared) == .finalizing)
    }

    /// A duplicate is not a failure: the three outcomes are counted apart.
    @Test func aFinishedRunCountsItsThreeOutcomesApart() {
        func book(_ title: String, _ status: UploadedBookStatus) -> UploadedBook {
            UploadedBook(title: title, status: status, rootRelPath: nil, detail: nil)
        }
        let state = UploadBooksUiStateFinished(
            imported: [book("A", .imported), book("B", .imported)],
            duplicates: [book("C", .duplicate)],
            failed: []
        )

        #expect(UploadBooksObserver.phase(from: state) == .finished(
            UploadOutcomeModel(imported: 2, duplicates: 1, failed: 0)
        ))
    }

    @Test func aFailedSessionCarriesItsMessage() {
        let appError = ServerConnectErrorInvalidUrl(correlationId: nil, debugInfo: nil, reason: "bad")

        #expect(UploadBooksObserver.phase(from: UploadBooksUiStateError(error: appError))
            == .error(message: appError.message))
    }

    /// Busy is when leaving would abandon files already on the wire, or race the server's import.
    @Test func onlyUploadingAndFinalizingAreBusy() {
        #expect(UploadBooksPhase.uploading(
            UploadProgressModel(filename: "a", fileNumber: 1, fileCount: 1, fraction: nil)
        ).isBusy)
        #expect(UploadBooksPhase.finalizing.isBusy)
        #expect(!UploadBooksPhase.idle.isBusy)
        #expect(!UploadBooksPhase.error(message: "x").isBusy)
        #expect(!UploadBooksPhase.finished(UploadOutcomeModel(imported: 1, duplicates: 0, failed: 0)).isBusy)
    }
}

@Suite("Upload refusal notices")
struct UploadNoticeTests {
    @Test func tooManyFilesNamesBothCounts() {
        let notice = UploadBooksObserver.notice(for: UploadSelectionRefusalTooManyFiles(count: 1_200, limit: 1_000))

        #expect(notice.title == String(localized: "admin.upload_books_too_many_files_title"))
        #expect(notice.message == String(
            format: String(localized: "admin.upload_books_too_many_files_body"), 1_000, 1_200
        ))
    }

    @Test func aFileTooLargeNamesTheFile() {
        let notice = UploadBooksObserver.notice(
            for: UploadSelectionRefusalFileTooLarge(filename: "huge.m4b", bytes: 20 << 30, limitBytes: 16 << 30)
        )

        #expect(notice.title == String(localized: "admin.upload_books_file_too_large_title"))
        #expect(notice.message.contains("huge.m4b"))
    }

    @Test func aSelectionTooLargeSaysSo() {
        let notice = UploadBooksObserver.notice(
            for: UploadSelectionRefusalTooLarge(bytes: 70 << 30, limitBytes: 64 << 30)
        )

        #expect(notice.title == String(localized: "admin.upload_books_too_large_title"))
    }

    @Test func anEmptySelectionSaysNothingWasFound() {
        #expect(UploadNotice.nothingFound.message == String(localized: "admin.upload_books_nothing_found"))
    }
}
