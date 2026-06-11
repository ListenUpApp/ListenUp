import Testing
@preconcurrency import Shared
@testable import ListenUp

@Suite("DirectoryItem mapping")
@MainActor
struct DirectoryItemMappingTests {
    private func entry(
        name: String = "Audiobooks",
        path: String = "/media/Audiobooks",
        hasChildren: Bool = true,
        itemCount: Int32 = 12
    ) -> DirectoryEntry {
        DirectoryEntry(name: name, path: path, hasChildren: hasChildren, itemCount: itemCount)
    }

    @Test func flattensCoreFields() {
        let item = DirectoryItem(from: entry(), selectedPaths: [])
        #expect(item.name == "Audiobooks")
        #expect(item.path == "/media/Audiobooks")
        #expect(item.itemCount == 12)
        #expect(item.hasChildren == true)
        #expect(item.id == "/media/Audiobooks")
    }

    @Test func isSelectedWhenPathInSelection() {
        let item = DirectoryItem(from: entry(), selectedPaths: ["/media/Audiobooks"])
        #expect(item.isSelected == true)
    }

    @Test func isNotSelectedWhenPathAbsent() {
        let item = DirectoryItem(from: entry(), selectedPaths: ["/other"])
        #expect(item.isSelected == false)
    }

    @Test func leafDirectoryHasNoChildren() {
        let item = DirectoryItem(from: entry(hasChildren: false, itemCount: 0), selectedPaths: [])
        #expect(item.hasChildren == false)
        #expect(item.itemCount == 0)
    }
}

@Suite("ScanProgressItem mapping")
@MainActor
struct ScanProgressItemMappingTests {
    private func state(
        current: Int32 = 0,
        filesTotal: Int32 = 0,
        books: Int32 = 0,
        authors: Int32 = 0,
        durationMs: Int64 = 0,
        currentFile: String? = nil
    ) -> ScanProgressState {
        ScanProgressState(
            phase: "analyzing",
            current: current,
            total: filesTotal,
            added: 0,
            updated: 0,
            removed: 0,
            filesTotal: filesTotal,
            books: books,
            authors: authors,
            durationMs: durationMs,
            currentFile: currentFile,
            recentBooks: [],
            startedAtMs: 0
        )
    }

    @Test func fractionIsCurrentOverTotal() {
        let item = ScanProgressItem(from: state(current: 666, filesTotal: 1_647))
        #expect(abs(item.fraction - (666.0 / 1_647.0)) < 0.0001)
    }

    @Test func fractionIsZeroWhenTotalIsZero() {
        let item = ScanProgressItem(from: state(current: 5, filesTotal: 0))
        #expect(item.fraction == 0)
    }

    @Test func fractionClampsToOne() {
        let item = ScanProgressItem(from: state(current: 2_000, filesTotal: 1_000))
        #expect(item.fraction == 1)
    }

    @Test func filesLabelGroupsThousands() {
        let item = ScanProgressItem(from: state(current: 666, filesTotal: 1_647))
        #expect(item.filesLabel == "666 / 1,647 files")
    }

    @Test func carriesBookAndAuthorCounts() {
        let item = ScanProgressItem(from: state(books: 42, authors: 7))
        #expect(item.books == 42)
        #expect(item.authors == 7)
    }

    @Test func hoursRoundFromDurationMs() {
        // 2h30m -> rounds to 3 hours.
        let item = ScanProgressItem(from: state(durationMs: 9_000_000))
        #expect(item.hours == 3)
    }

    @Test func hoursAreZeroForShortDuration() {
        // 10 minutes -> rounds to 0 hours.
        let item = ScanProgressItem(from: state(durationMs: 600_000))
        #expect(item.hours == 0)
    }

    @Test func currentFilePassesThrough() {
        let item = ScanProgressItem(from: state(currentFile: "/media/book/ch01.mp3"))
        #expect(item.currentFile == "/media/book/ch01.mp3")
    }
}
