import Foundation
@preconcurrency import Shared

/// Observes `ABSImportHubViewModel`'s **list** surface — the persistent/resumable roster of
/// staged ABS imports — and flattens `ABSImportListUiState` (`Loading` / `Ready` / `Error`)
/// into a SwiftUI-native `ImportHubPhase` the hub screen binds to.
///
/// The hub screen is the entry to the import flow: it shows existing imports (resumable) and an
/// empty state, and launches the wizard for a new one. The tabbed per-import detail
/// (Overview / Users / Books / Sessions) the VM also drives is intentionally NOT surfaced on
/// iOS — the mockup models a linear wizard, so a freshly created import flows straight into the
/// `ImportFlowObserver` wizard. Resuming an in-progress import is a deferred follow-up.
///
/// Row models and the status classification live in pure, testable initializers / statics.
/// Thin over `FlowBridge`, mirroring `AdminObserver`.
@Observable
@MainActor
final class ABSImportHubObserver {
    // MARK: - State

    private(set) var phase: ImportHubPhase = .loading

    // MARK: - Dependencies

    private let viewModel: ABSImportHubViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: ABSImportHubViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.listState) { [weak self] in self?.apply($0) }
    }

    deinit {
        MainActor.assumeIsolated { bridge.cancelAll() }
    }

    func stopObserving() {
        bridge.cancelAll()
    }

    // MARK: - Actions

    func reload() { viewModel.loadImports() }
    func deleteImport(id: String) { viewModel.deleteImport(importId: id) }
    func clearError() { viewModel.clearError() }

    // MARK: - State mapping

    private func apply(_ state: ABSImportListUiState) {
        phase = Self.phase(from: state)
    }

    /// Pure: project the sealed `ABSImportListUiState` onto the hub screen's phase.
    /// `nonisolated` so tests can exercise it off the main actor.
    nonisolated static func phase(from state: ABSImportListUiState) -> ImportHubPhase {
        switch onEnum(of: state) {
        case .loading:
            return .loading
        case .ready(let ready):
            return .ready(ImportHubReadyModel(from: ready))
        case .error(let error):
            return .error(message: error.message)
        }
    }
}

// MARK: - Phase

/// Flattened hub-list state for a SwiftUI `switch`.
enum ImportHubPhase: Equatable {
    case loading
    case ready(ImportHubReadyModel)
    case error(message: String)
}

// MARK: - Ready model

/// The hub list snapshot: the staged imports (newest first), the create-in-flight overlay, and
/// any transient mutation error.
struct ImportHubReadyModel: Equatable {
    let imports: [ImportSummaryRowModel]
    let isCreating: Bool
    let error: String?

    init(from ready: ABSImportListUiStateReady) {
        self.imports = ready.imports.map(ImportSummaryRowModel.init(from:))
        self.isCreating = ready.isCreating
        self.error = ready.error
    }

    init(imports: [ImportSummaryRowModel], isCreating: Bool, error: String?) {
        self.imports = imports
        self.isCreating = isCreating
        self.error = error
    }
}

// MARK: - Summary row model

/// The progress stage of a staged import, for the row's badge.
enum ImportStage: Equatable {
    case analyzing
    case pending
    case ready
    case imported
    case other(String)

    /// Pure: classify the wire `status` string into a display stage. Mirrors the Android
    /// hub's status badges.
    static func from(status: String) -> ImportStage {
        switch status.lowercased() {
        case "analyzing": return .analyzing
        case "uploaded", "pending", "analyzed": return .pending
        case "mapped", "ready": return .ready
        case "applied", "imported": return .imported
        default: return .other(status)
        }
    }
}

/// One row in the imports list: name, created date, stage badge, and the tallies for a subtitle.
struct ImportSummaryRowModel: Identifiable, Equatable {
    let id: String
    let name: String
    let createdAt: Date?
    let stage: ImportStage
    let totalUsers: Int
    let totalBooks: Int
    let sessionsImported: Int

    init(from summary: ABSImportSummary) {
        self.id = summary.id
        self.name = summary.name
        self.createdAt = ISO8601DateParser.date(from: summary.createdAt)
        self.stage = ImportStage.from(status: summary.status)
        self.totalUsers = Int(summary.totalUsers)
        self.totalBooks = Int(summary.totalBooks)
        self.sessionsImported = Int(summary.sessionsImported)
    }

    init(
        id: String,
        name: String,
        createdAt: Date?,
        stage: ImportStage,
        totalUsers: Int,
        totalBooks: Int,
        sessionsImported: Int
    ) {
        self.id = id
        self.name = name
        self.createdAt = createdAt
        self.stage = stage
        self.totalUsers = totalUsers
        self.totalBooks = totalBooks
        self.sessionsImported = sessionsImported
    }
}
