import Foundation
import Shared

/// Observes `OrganizeSettingsViewModel` — flattens the sealed `OrganizeSettingsUiState` into a
/// native `OrganizePhase`, and turns the one-shot `events` into an `OrganizeEffect` the view acts on.
///
/// Failures reach the user through the shared `ErrorBus`, which the ViewModel already emits to and
/// `GlobalErrorObserver` presents as the app-wide alert (iosApp rule 10). `Ready.error` is therefore
/// not mapped: showing it here too would put the same failure on screen twice.
///
/// Thin over `FlowBridge`, mirroring `AdminBackupsObserver`.
@Observable
@MainActor
final class OrganizeSettingsObserver {
    // MARK: - State

    private(set) var phase: OrganizePhase = .loading

    /// The outcome of the last Save or Organize that changed nothing on screen, shown beneath the
    /// actions until the next edit or action. Nil when there is nothing to say.
    private(set) var notice: String?
    /// Counts notices, so the view's success haptic fires for each — including the same one twice.
    private(set) var noticeCount = 0

    // MARK: - Dependencies

    private let viewModel: OrganizeSettingsViewModel
    private let bridge = FlowBridge()

    init(viewModel: OrganizeSettingsViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.phase = Self.phase(from: $0) }
        bridge.bind(viewModel.events) { [weak self] in self?.apply(Self.effect(of: $0)) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func setPreset(_ preset: OrganizePreset) { notice = nil; viewModel.setPreset(preset: preset) }
    func setSeriesPrefix(_ prefix: OrganizeSeriesPrefix) { notice = nil; viewModel.setSeriesPrefix(prefix: prefix) }
    func setAuthorForm(_ form: OrganizeAuthorForm) { notice = nil; viewModel.setAuthorForm(form: form) }
    func saveRules() { notice = nil; viewModel.saveRules() }
    func organize() { notice = nil; viewModel.organize() }
    func confirmOrganize() { viewModel.confirmOrganize() }
    func dismissPreview() { viewModel.dismissPreview() }
    func dismissRunReport() { viewModel.dismissRunReport() }
    func resumeAfterFailure() { viewModel.resumeAfterFailure() }

    // MARK: - Mapping

    private func apply(_ effect: OrganizeEffect) {
        let message = effect.message
        notice = message
        noticeCount += 1
        VoiceOverAnnouncement.post(message)
    }

    nonisolated static func phase(from state: OrganizeSettingsUiState) -> OrganizePhase {
        switch state.sealedType() {
        case .loading:
            return .loading
        case .ready(let readyType):
            return .ready(OrganizeReadyModel.from(readyType.value))
        case .error(let errorType):
            return .error(message: errorType.value.error.message)
        }
    }

    nonisolated static func effect(of event: OrganizeSettingsEvent) -> OrganizeEffect {
        switch event.sealedType() {
        case .rulesSaved: return .confirmSaved
        case .alreadyOrganized: return .alreadyOrganized
        }
    }
}

// MARK: - Phase and effects

/// Flattened organizer state for a SwiftUI `switch`.
enum OrganizePhase: Equatable {
    case loading
    case ready(OrganizeReadyModel)
    case error(message: String)
}

/// What a one-shot `OrganizeSettingsEvent` says on iOS. Neither outcome changes anything on screen,
/// so each is a line beneath the actions, a success haptic and a VoiceOver announcement — not an
/// alert, which the HIG keeps for information people must act on ("Avoid using an alert merely to
/// provide information", HIG, Alerts), and not a toast (HIG, Feedback).
enum OrganizeEffect: Equatable {
    /// The rules were saved and nothing moved.
    case confirmSaved
    /// Organize found nothing to do: every book is already where the rules say.
    case alreadyOrganized

    var message: String {
        switch self {
        case .confirmSaved: String(localized: "admin.organize_saved")
        case .alreadyOrganized: String(localized: "admin.organize_already")
        }
    }
}

// MARK: - Ready model

/// The organizer's edit buffer and its two overlays (the consent preview and the run), as values.
struct OrganizeReadyModel: Equatable {
    let preset: OrganizePreset
    let seriesPrefix: OrganizeSeriesPrefix
    let authorForm: OrganizeAuthorForm
    /// A load, preview or save is in flight.
    let isWorking: Bool
    /// The consent sheet's content; non-nil while it is presented.
    let preview: OrganizePreviewModel?
    /// Progress while a run moves files, then its report; nil when no run is showing.
    let run: OrganizeRunModel?

    /// A series number only matters where there is a series folder.
    var showsSeriesPrefix: Bool { preset == .authorSeriesTitle }
    /// An author style only matters where there is an author folder.
    var showsAuthorForm: Bool { preset != .flatTitle }
    /// Files are moving: no second sweep, and no rule change under it.
    var isRunning: Bool { run.map { !$0.isFinished } ?? false }

    /// The choices, in the order Android lists them.
    static var presets: [OrganizePreset] { [.authorSeriesTitle, .authorTitle, .flatTitle] }
    static var seriesPrefixes: [OrganizeSeriesPrefix] { [.bookNDash, .nDash, .bracketN, .none] }
    static var authorForms: [OrganizeAuthorForm] { [.firstLast, .lastFirst] }

    nonisolated static func from(_ ready: OrganizeSettingsUiStateReady) -> OrganizeReadyModel {
        OrganizeReadyModel(
            preset: ready.settings.preset,
            seriesPrefix: ready.settings.seriesPrefix,
            authorForm: ready.settings.authorForm,
            isWorking: ready.isWorking,
            preview: ready.preview.map(OrganizePreviewModel.from),
            run: ready.run.map(OrganizeRunModel.from)
        )
    }

    static func title(_ preset: OrganizePreset) -> String {
        switch preset {
        case .authorSeriesTitle: String(localized: "admin.organize_preset_author_series_title")
        case .authorTitle: String(localized: "admin.organize_preset_author_title")
        case .flatTitle: String(localized: "admin.organize_preset_flat_title")
        }
    }

    static func title(_ prefix: OrganizeSeriesPrefix) -> String {
        switch prefix {
        case .bookNDash: String(localized: "admin.organize_prefix_book_n_dash")
        case .nDash: String(localized: "admin.organize_prefix_n_dash")
        case .bracketN: String(localized: "admin.organize_prefix_bracket_n")
        case .none: String(localized: "admin.organize_prefix_none")
        }
    }

    static func title(_ form: OrganizeAuthorForm) -> String {
        switch form {
        case .firstLast: String(localized: "admin.organize_author_first_last")
        case .lastFirst: String(localized: "admin.organize_author_last_first")
        }
    }
}

// MARK: - Preview

/// The consent sheet: the scope of the sweep and a sample of what it does.
///
/// The scope is two lines, not one, because a plan holds two kinds of work. Folder moves get the
/// moves line; books already in the right folder whose audio file is misnamed get the renames line.
/// A plan of only renames shows only the second — "Moves 0 files across 0 folders" would read as a
/// no-op for work that is real.
struct OrganizePreviewModel: Equatable {
    let movesSummary: String?
    let renamesSummary: String?
    let rows: [OrganizePreviewRow]
    /// Planned books not listed in `rows` — "…and N more".
    let moreCount: Int

    /// How many before→after rows the sheet lists, as on Android.
    static let rowsShown = 8

    nonisolated static func from(_ preview: OrganizePreviewDto) -> OrganizePreviewModel {
        let rows = preview.entries.prefix(rowsShown).map(OrganizePreviewRow.from)
        let planned = Int(preview.bookCount) + Int(preview.renamedInPlaceCount)
        return OrganizePreviewModel(
            movesSummary: preview.bookCount > 0
                ? String(
                    format: String(localized: "admin.organize_confirm_summary"),
                    Int(preview.fileCount), Int(preview.bookCount), Int(preview.collisionCount)
                )
                : nil,
            renamesSummary: preview.renamedInPlaceCount > 0
                ? String(format: String(localized: "admin.organize_confirm_renames"), Int(preview.renamedInPlaceCount))
                : nil,
            rows: Array(rows),
            moreCount: max(planned - rows.count, 0)
        )
    }
}

/// One before→after line of the plan.
struct OrganizePreviewRow: Equatable, Identifiable {
    let id: String
    let before: String
    let after: String

    /// An in-place rename's folder is unchanged, so its filenames are the story; showing the folder
    /// on both sides would make a real edit read as a no-op. A move shows the folder it leaves.
    nonisolated static func from(_ entry: OrganizePreviewEntryDto) -> OrganizePreviewRow {
        OrganizePreviewRow(
            id: entry.bookId,
            before: entry.renamedFrom ?? (entry.fromPath.split(separator: "/").last.map(String.init) ?? entry.fromPath),
            after: entry.renamedTo ?? entry.toPath
        )
    }
}

// MARK: - Run

/// A run's progress while files move, and its report once they stop.
struct OrganizeRunModel: Equatable {
    let completed: Int
    let total: Int
    let movedBooks: Int
    let failedBooks: Int
    let isFinished: Bool

    /// Finished with at least one book that failed — the report offers Resume.
    var hasFailures: Bool { isFinished && failedBooks > 0 }

    /// 0...1. Zero until the server says how many books there are.
    var fraction: Double { total > 0 ? min(Double(completed) / Double(total), 1) : 0 }

    nonisolated static func from(_ run: OrganizeRunProgress) -> OrganizeRunModel {
        OrganizeRunModel(
            completed: Int(run.completed),
            total: Int(run.total),
            movedBooks: Int(run.movedBooks),
            failedBooks: Int(run.failedBooks),
            isFinished: run.terminal
        )
    }
}
