import Foundation
import Shared

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

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func reload() { viewModel.refresh() }
    func deleteImport(id: String) { viewModel.deleteImport(importId: ImportId(value: id)) }
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
            return .error(message: error.error.message)
        case .unknown:
            Log.error("Unexpected ABSImportListUiState case")
            return .error(message: String(localized: "common.something_went_wrong"))
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

/// The hub list snapshot: the staged imports (newest first) and any transient mutation error
/// surfaced as a snackbar/alert without clearing the list.
struct ImportHubReadyModel: Equatable {
    let imports: [ImportSummaryRowModel]
    let error: String?

    init(from ready: ABSImportListUiStateReady) {
        self.imports = ready.imports.map(ImportSummaryRowModel.init(from:))
        self.error = ready.error?.message
    }

    init(imports: [ImportSummaryRowModel], error: String?) {
        self.imports = imports
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

    /// Pure: classify the typed `ImportStatus` into a display stage. Mirrors the Android
    /// hub's status badges. Switches on the bridged Kotlin enum so the four real lifecycle
    /// states (`uploaded`/`analyzed`/`mapped`/`applied`) are exhaustive and a contract change
    /// surfaces at compile time. (Swift Export camelCases the Kotlin `SCREAMING_SNAKE` cases.)
    static func from(status: ImportStatus) -> ImportStage {
        switch status {
        case .uploaded, .analyzed: return .pending
        case .mapped: return .ready
        case .applied: return .imported
        @unknown default: return .other(status.description)
        }
    }
}

/// One row in the imports list: the created timestamp (the only human-facing identifier the lean
/// `ImportSummary` carries), a stage badge, and the book tally for the subtitle.
struct ImportSummaryRowModel: Identifiable, Equatable {
    let id: String
    let createdAt: Date
    let stage: ImportStage
    let bookCount: Int

    init(from summary: ImportSummary) {
        self.id = summary.idString
        self.createdAt = Date(timeIntervalSince1970: Double(summary.createdAt) / 1000)
        self.stage = ImportStage.from(status: summary.status)
        self.bookCount = Int(summary.bookCount)
    }

    init(id: String, createdAt: Date, stage: ImportStage, bookCount: Int) {
        self.id = id
        self.createdAt = createdAt
        self.stage = stage
        self.bookCount = bookCount
    }
}
