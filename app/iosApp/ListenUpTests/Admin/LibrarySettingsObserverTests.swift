import Testing
import Shared
@testable import ListenUp

/// The value-type mappings the `LibrarySettingsObserver` performs at its boundary — the
/// guard against feeding Swift-Export-bridged Kotlin objects into a `ForEach` (iosApp rule 8).
/// Mirrors `DirectoryItemMappingTests` (the established native-mapping test style).
@Suite("LibrarySettings folder row mapping")
struct LibraryFolderRowModelTests {
    @Test func mapsIdAndRootPath() {
        let model = LibraryFolderRowModel(from: LibraryFolderRef(id: "f1", rootPath: "/media/books"))
        #expect(model.id == "f1")
        #expect(model.displayPath == "/media/books")
    }

    @Test func fallsBackToIdWhenPathRedacted() {
        let model = LibraryFolderRowModel(from: LibraryFolderRef(id: "f2", rootPath: nil))
        #expect(model.id == "f2")
        #expect(model.displayPath == "f2")
    }
}

@Suite("LibrarySettings browser entry mapping")
struct BrowserEntryModelTests {
    @Test func mapsNamePathAndId() {
        let model = BrowserEntryModel(from: DirectoryEntryResponse(name: "Books", path: "/media/books"))
        #expect(model.name == "Books")
        #expect(model.path == "/media/books")
        // id is derived from path so SwiftUI diffs entries by their absolute path.
        #expect(model.id == "/media/books")
    }
}

/// Try Again on a library-settings screen whose load failed reaches the shared ViewModel, which starts over.
@Suite("LibrarySettings retry")
struct LibrarySettingsRetryTests {
    @Test func tryAgainAfterAFailedLoadStartsTheLoadOver() async throws {
        // No server is configured under test, so the first load fails.
        let viewModel = Dependencies.shared.createLibrarySettingsViewModel()
        let observer = LibrarySettingsObserver(viewModel: viewModel)
        for _ in 0..<100 where !(viewModel.state.value is LibrarySettingsUiStateError) {
            try await Task.sleep(for: .milliseconds(100))
        }
        try #require(viewModel.state.value is LibrarySettingsUiStateError)

        observer.retry()

        #expect(viewModel.state.value is LibrarySettingsUiStateLoading)
    }
}
