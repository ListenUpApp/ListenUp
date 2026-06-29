import Foundation
import Shared

/// Observes `LibrarySettingsViewModel` — flattens the sealed `LibrarySettingsUiState`
/// into a SwiftUI-native `LibrarySettingsPhase` (`loading` / `ready` / `error`) and maps the
/// bridged Kotlin folder + directory lists into native value types at this boundary, so the
/// view never feeds a Swift-Export-bridged object into a `ForEach` (iosApp rule 8).
///
/// Errors are mapped once here (per iosApp rule 10): the terminal `Error` case becomes an
/// `error(String)` phase, and the transient `Ready.error` becomes `Ready.transientError`.
///
/// Thin over `FlowBridge`, mirroring `AdminObserver` / `LibrarySetupViewModelWrapper`.
@Observable
@MainActor
final class LibrarySettingsObserver {
    // MARK: - State

    private(set) var phase: LibrarySettingsPhase = .loading

    // MARK: - Dependencies

    /// Optional so the bridge-free test initializer can drive `apply` without a live KMP VM;
    /// production always wires it. Action methods are safe no-ops when nil.
    private let viewModel: LibrarySettingsViewModel?
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: LibrarySettingsViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    /// Bridge-free initializer for observer-level unit tests. Skips flow binding so the
    /// stateful mapping (`apply`) can be driven directly without a live KMP ViewModel.
    init() {
        self.viewModel = nil
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func addFolder(_ path: String) { viewModel?.addScanPath(path: path) }
    func removeFolder(id: String) { viewModel?.removeFolder(folderId: id) }
    func rescan() { viewModel?.triggerScan() }
    func showFolderBrowser(_ show: Bool) { viewModel?.setShowFolderBrowser(show: show) }
    func openBrowserDirectory(_ path: String) { viewModel?.loadBrowserDirectory(path: path) }
    func browserNavigateUp() { viewModel?.browserNavigateUp() }
    func clearError() { viewModel?.clearError() }

    // MARK: - State mapping

    /// Internal (not private) so observer-level tests can drive the mapping directly.
    func apply(_ state: LibrarySettingsUiState) {
        switch onEnum(of: state) {
        case .loading:
            phase = .loading
        case .ready(let ready):
            phase = .ready(LibrarySettingsReadyModel(from: ready))
        case .error(let error):
            phase = .error(error.error.message)
        case .unknown:
            Log.error("Unexpected LibrarySettingsUiState case")
            phase = .loading
        }
    }
}

// MARK: - Phase

/// Flattened library-settings state for a SwiftUI `switch`.
enum LibrarySettingsPhase {
    case loading
    case ready(LibrarySettingsReadyModel)
    case error(String)
}

// MARK: - Ready model

/// The fully-flattened `Ready` snapshot the screen renders. All bridged Kotlin lists are
/// mapped to native value types here so the view's `ForEach`es never touch a bridged object.
struct LibrarySettingsReadyModel: Equatable {
    let folders: [LibraryFolderRowModel]
    /// The last live folder can't be removed (a library must keep at least one scan path).
    let canRemoveFolders: Bool
    let isSaving: Bool
    let isScanning: Bool
    /// A transient mutation failure, surfaced as a dismissible alert.
    let transientError: String?

    // Folder browser overlay
    let showFolderBrowser: Bool
    let isBrowserLoading: Bool
    let browserPath: String
    let browserParent: String?
    let browserEntries: [BrowserEntryModel]
    let browserIsRoot: Bool

    init(from ready: LibrarySettingsUiStateReady) {
        self.folders = ready.library.folders.map(LibraryFolderRowModel.init(from:))
        self.canRemoveFolders = ready.library.folders.count > 1
        self.isSaving = ready.isSaving
        self.isScanning = ready.isScanning
        self.transientError = ready.error?.message
        self.showFolderBrowser = ready.showFolderBrowser
        self.isBrowserLoading = ready.isBrowserLoading
        self.browserPath = ready.browserPath
        self.browserParent = ready.browserParent
        self.browserEntries = ready.browserEntries.map(BrowserEntryModel.init(from:))
        self.browserIsRoot = ready.browserIsRoot
    }
}

// MARK: - Row models

/// One library scan folder, flattened for SwiftUI display.
struct LibraryFolderRowModel: Identifiable, Equatable {
    let id: String
    /// The folder's root path, falling back to its id when the server redacted the path.
    /// (Non-admins never reach this screen, so in practice this is always the real path.)
    let displayPath: String

    init(from folder: LibraryFolderRef) {
        self.id = folder.id
        self.displayPath = folder.rootPath ?? folder.id
    }

    init(id: String, displayPath: String) {
        self.id = id
        self.displayPath = displayPath
    }
}

/// One server-side directory in the folder browser, flattened for SwiftUI display.
struct BrowserEntryModel: Identifiable, Equatable {
    var id: String { path }
    let name: String
    let path: String

    init(from entry: DirectoryEntryResponse) {
        self.name = entry.name
        self.path = entry.path
    }

    init(name: String, path: String) {
        self.name = name
        self.path = path
    }
}
