import Foundation
@preconcurrency import Shared

/// A server-side directory, flattened for SwiftUI display.
struct DirectoryItem: Identifiable, Equatable {
    var id: String { path }
    let name: String
    let path: String
    let itemCount: Int
    let hasChildren: Bool
    let isSelected: Bool

    /// Flatten a KMP ``DirectoryEntry`` against the current selection set.
    init(from entry: DirectoryEntry, selectedPaths: Set<String>) {
        name = entry.name
        path = entry.path
        itemCount = Int(entry.itemCount)
        hasChildren = entry.hasChildren
        isSelected = selectedPaths.contains(entry.path)
    }
}

/// Live scan progress, flattened for the "Building your library" screen.
struct ScanProgressItem: Equatable {
    /// Progress over discovered files, or `nil` during the indeterminate "walking" phase
    /// (total still unknown). Sourced from the shared contract's `progressFraction`.
    let fraction: Double?
    /// Human-readable file counter, e.g. `"666 / 1,647 files"`.
    let filesLabel: String
    let currentFile: String?
    let books: Int
    let authors: Int
    let hours: Int

    /// Flatten a KMP ``ScanProgressState`` into display-ready values.
    init(from state: ScanProgressState) {
        let total = Int(state.filesTotal)
        let current = Int(state.current)
        fraction = state.progressFraction.map { Double($0) }
        filesLabel = "\(Self.grouped(current)) / \(Self.grouped(total)) files"
        currentFile = state.currentFile
        books = Int(state.books)
        authors = Int(state.authors)
        hours = Int(state.hours)
    }

    private static func grouped(_ value: Int) -> String {
        Self.formatter.string(from: NSNumber(value: value)) ?? "\(value)"
    }

    private static let formatter: NumberFormatter = {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        return formatter
    }()
}

/// Observes ``LibrarySetupViewModel`` — the `LibrarySetupUiState`, its one-shot
/// `navActions` flow, and the cross-cutting `SyncRepository.scanProgress` — flattening
/// all three into SwiftUI-native state. Thin over `FlowBridge`. Errors are mapped once
/// here into ``errorMessage`` (no `errorBus`).
@Observable
@MainActor
final class LibrarySetupViewModelWrapper {
    // Status check
    private(set) var isCheckingStatus: Bool = true
    private(set) var needsSetup: Bool = false

    // Folder browser
    private(set) var currentPath: String = "/"
    private(set) var parentPath: String?
    private(set) var isRoot: Bool = true
    private(set) var isLoadingDirectories: Bool = false
    private(set) var directories: [DirectoryItem] = []

    /// The full selection set, spanning every directory the user has visited — not just
    /// the rows currently on screen. The visible `directories` only carry a per-row
    /// `isSelected` flag for rendering; this is the authoritative gate for "can create".
    private(set) var selectedPaths: Set<String> = []

    /// True once at least one folder anywhere in the tree is selected.
    var hasSelection: Bool { !selectedPaths.isEmpty }

    /// How many folders are selected across the whole tree.
    var selectionCount: Int { selectedPaths.count }

    // Library creation
    private(set) var isCreatingLibrary: Bool = false

    /// The library name. Two-way bound by the view; writes forward to the shared VM.
    var libraryName: String = "My Library" {
        didSet {
            guard libraryName != oldValue else { return }
            viewModel.setLibraryName(name: libraryName)
        }
    }

    // Scan progress (cross-cutting, from SyncRepository)
    private(set) var scan: ScanProgressItem?

    // Error (mapped once from state.error)
    private(set) var errorMessage: String?

    /// Navigation callbacks — set by the view.
    var onLibraryCreated: (() -> Void)?
    var onFinished: (() -> Void)?

    /// The shared VM. Implicitly-unwrapped because the bridge-free test initializer
    /// (`init()`) leaves it nil — production always wires it. Action methods touch it;
    /// the state-mapping path (`apply`) does not, which is what tests exercise.
    private let viewModel: LibrarySetupViewModel!
    private let bridge = FlowBridge()

    init(viewModel: LibrarySetupViewModel, syncRepository: any SyncRepository) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.navActions) { [weak self] in self?.applyNav($0) }
        bridge.bind(syncRepository.scanProgress) { [weak self] state in
            self?.scan = state.map(ScanProgressItem.init(from:))
        }
    }

    /// Bridge-free initializer for wrapper-level unit tests. Skips flow binding so the
    /// stateful mapping (`apply`) can be driven directly without a live KMP ViewModel.
    init() {
        self.viewModel = nil
    }

    func stopObserving() {
        bridge.cancelAll()
    }

    // MARK: - Actions

    func checkStatus() {
        viewModel.checkLibraryStatus()
    }

    func open(_ path: String) {
        viewModel.loadDirectory(path: path)
    }

    func up() {
        viewModel.navigateUp()
    }

    func toggle(_ path: String) {
        viewModel.togglePath(path: path)
    }

    /// Add the current directory to the selection (mirrors the wizard's
    /// "use this folder" affordance). `selectPath` is additive in the shared VM.
    func selectCurrent() {
        viewModel.selectPath(path: currentPath)
    }

    func clearSelection() {
        viewModel.clearSelection()
    }

    func create() {
        viewModel.createLibrary()
    }

    func finish() {
        viewModel.finishOnboarding()
    }

    func dismissError() {
        viewModel.clearError()
    }

    // MARK: - State mapping

    /// Internal (not private) so wrapper-level tests can drive the stateful selection
    /// logic directly without a live KMP ViewModel.
    func apply(_ state: LibrarySetupUiState) {
        isCheckingStatus = state.isCheckingStatus
        needsSetup = state.needsSetup
        currentPath = state.currentPath
        parentPath = state.parentPath
        isRoot = state.isRoot
        isLoadingDirectories = state.isLoadingDirectories
        isCreatingLibrary = state.isCreatingLibrary
        errorMessage = state.error

        let selected = state.selectedPaths
        selectedPaths = selected
        directories = state.directories.map {
            DirectoryItem(from: $0, selectedPaths: selected)
        }

        // Keep the editable name in sync with the source of truth without
        // re-entering the `didSet` forward (the value is already identical there).
        if libraryName != state.libraryName {
            libraryName = state.libraryName
        }
    }

    private func applyNav(_ action: LibrarySetupNavAction) {
        switch onEnum(of: action) {
        case .libraryCreated:
            onLibraryCreated?()
        case .finished:
            onFinished?()
        }
    }
}
