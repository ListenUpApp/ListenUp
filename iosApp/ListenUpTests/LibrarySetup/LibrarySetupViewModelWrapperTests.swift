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

    @Test func fractionIsCurrentOverTotal() throws {
        let item = ScanProgressItem(from: state(current: 666, filesTotal: 1_647))
        let fraction = try #require(item.fraction)
        #expect(abs(fraction - (666.0 / 1_647.0)) < 0.0001)
    }

    @Test func fractionIsNilWhenTotalIsZero() {
        // Walking phase: no totals yet → indeterminate, not a fake 0%.
        let item = ScanProgressItem(from: state(current: 5, filesTotal: 0))
        #expect(item.fraction == nil)
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

@Suite("LibrarySetupViewModelWrapper selection gate")
@MainActor
struct LibrarySetupWrapperSelectionTests {
    /// Build a `LibrarySetupUiState` with explicit `selectedPaths` and `directories` so we
    /// can assert the gate reads the TOTAL selection, not just the visible rows.
    private func uiState(
        directories: [DirectoryEntry] = [],
        selectedPaths: Set<String> = []
    ) -> LibrarySetupUiState {
        LibrarySetupUiState(
            isCheckingStatus: false,
            needsSetup: true,
            currentPath: "/media",
            parentPath: "/",
            directories: directories,
            isLoadingDirectories: false,
            isRoot: false,
            selectedPaths: selectedPaths,
            libraryName: "My Library",
            isCreatingLibrary: false,
            createdLibraries: [],
            setupComplete: false,
            error: nil
        )
    }

    private func entry(path: String, hasChildren: Bool = false) -> DirectoryEntry {
        DirectoryEntry(
            name: path.split(separator: "/").last.map(String.init) ?? path,
            path: path,
            hasChildren: hasChildren,
            itemCount: 0
        )
    }

    /// The C1 regression: a leaf selected under `/media/A`, then the user navigates Up to
    /// `/media` (whose visible `directories` no longer contain the selected path). The gate
    /// must still enable Create because the selection lives in `selectedPaths`, not the rows.
    @Test func hasSelectionSurvivesNavigatingAwayFromSelectedFolder() {
        let wrapper = LibrarySetupViewModelWrapper()
        wrapper.apply(uiState(
            directories: [entry(path: "/media/B"), entry(path: "/media/C")],
            selectedPaths: ["/media/A/book"]
        ))

        #expect(wrapper.hasSelection == true)
        #expect(wrapper.selectionCount == 1)
        #expect(wrapper.selectedPaths == ["/media/A/book"])
        // The selected path is deliberately absent from the visible rows.
        #expect(wrapper.directories.contains { $0.isSelected } == false)
    }

    @Test func emptySelectionDisablesGate() {
        let wrapper = LibrarySetupViewModelWrapper()
        wrapper.apply(uiState(directories: [entry(path: "/media/B")], selectedPaths: []))

        #expect(wrapper.hasSelection == false)
        #expect(wrapper.selectionCount == 0)
    }

    @Test func selectionCountSpansMultipleDirectories() {
        let wrapper = LibrarySetupViewModelWrapper()
        wrapper.apply(uiState(
            directories: [entry(path: "/media/B")],
            selectedPaths: ["/media/A/book", "/media/B", "/other/D"]
        ))

        #expect(wrapper.selectionCount == 3)
        // Only the currently-visible "/media/B" renders as selected.
        #expect(wrapper.directories.filter { $0.isSelected }.count == 1)
    }
}
