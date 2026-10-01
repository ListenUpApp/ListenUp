import Testing
import Shared
@testable import ListenUp

/// A held book's download stays listed and is marked; Edit warns before collections release it.
@Suite("Held book in Edit and Downloads")
struct HeldEditAndDownloadsTests {
    @Test func aHeldDownloadIsMarked() {
        let summary = DownloadedBookSummary(
            bookId: "b1",
            title: "The Ministry of Time",
            authorNames: "Kaliane Bradley",
            sizeBytes: 400_000_000,
            fileCount: 12,
            isHeld: true
        )
        #expect(DownloadedBookRow(summary).isHeld == true)
    }

    @Test func handBuiltRowsAreNotHeld() {
        let row = DownloadedBookRow(id: "b1", title: "T", authorNames: "A", sizeBytes: 1, fileCount: 1)
        #expect(row.isHeld == false)
    }

    @Test func onlyAnAdminEditingAHeldBookIsWarned() {
        #expect(BookEditObserver.warnsCollectionsRelease(isAdmin: true, isHeld: true))
        #expect(BookEditObserver.warnsCollectionsRelease(isAdmin: true, isHeld: false) == false)
        #expect(BookEditObserver.warnsCollectionsRelease(isAdmin: false, isHeld: true) == false)
    }
}
