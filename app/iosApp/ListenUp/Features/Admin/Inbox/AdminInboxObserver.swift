import Foundation
import Shared

/// Observes `AdminInboxViewModel` — flattens the sealed `AdminInboxUiState` into a
/// SwiftUI-native `AdminInboxPhase` and surfaces selection state, release overlay, and
/// transient error/result as flat properties the view binds to.
///
/// SSE inbox updates flow through the shared VM's `state` — no extra wiring needed here.
@Observable
@MainActor
final class AdminInboxObserver {
    // MARK: - State

    private(set) var phase: AdminInboxPhase = .loading

    // MARK: - Dependencies

    private let viewModel: AdminInboxViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: AdminInboxViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func reload() { viewModel.loadInboxBooks() }
    func toggleBookSelection(bookId: String) { viewModel.toggleBookSelection(bookId: bookId) }
    func selectAll() { viewModel.selectAll() }
    func clearSelection() { viewModel.clearSelection() }
    func releaseSelected() { viewModel.releaseSelected() }
    func clearError() { viewModel.clearError() }
    func clearReleaseResult() { viewModel.clearReleaseResult() }
    func dismissScanIssue(issueId: String) { viewModel.dismissScanIssue(issueId: issueId) }

    // MARK: - State mapping

    private func apply(_ state: AdminInboxUiState) {
        phase = Self.phase(from: state)
    }

    /// Pure: project the sealed `AdminInboxUiState` onto the flattened phase.
    /// `nonisolated` so tests can exercise it off the main actor (mirrors the backup observers).
    nonisolated static func phase(from state: AdminInboxUiState) -> AdminInboxPhase {
        switch state.sealedType() {
        case .loading:
            return .loading
        case .ready(let readyType):
            let ready = readyType.value
            return .ready(AdminInboxReadyModel(from: ready))
        case .error(let errorType):
            let error = errorType.value
            return .error(error.message)
        }
    }
}

// MARK: - Phase

enum AdminInboxPhase: Equatable {
    case loading
    case ready(AdminInboxReadyModel)
    case error(String)
}

// MARK: - Ready model

struct AdminInboxReadyModel: Equatable {
    let books: [InboxBookRowModel]
    let scanIssues: [ScanIssueRowModel]
    let selectedBookIds: Set<String>
    let isReleasing: Bool
    let lastReleasedCount: Int?
    let error: String?
    let bookCount: Int
    let hasBooks: Bool
    let hasIssues: Bool
    /// Empty means BOTH halves are empty. Issues with no held books is a populated inbox, and
    /// telling the admin it is empty while holding problems in it would be the screen
    /// contradicting itself. Mirrors `AdminInboxUiState.Ready.isEmpty`.
    let isEmpty: Bool
    let hasSelection: Bool
    let selectedCount: Int
    let allSelected: Bool

    init(from ready: AdminInboxUiStateReady) {
        self.books = ready.books.map(InboxBookRowModel.init(from:))
        self.scanIssues = ready.scanIssues.map(ScanIssueRowModel.init(from:))
        self.selectedBookIds = Set(ready.selectedBookIds)
        self.isReleasing = ready.isReleasing
        self.lastReleasedCount = ready.lastReleasedCount.map { Int($0) }
        self.error = ready.error
        self.bookCount = Int(ready.bookIds.count)
        self.hasBooks = ready.hasBooks
        self.hasIssues = ready.hasIssues
        self.isEmpty = ready.isEmpty
        self.hasSelection = ready.hasSelection
        self.selectedCount = Int(ready.selectedCount)
        self.allSelected = ready.allSelected
    }
}

// MARK: - Row model

struct InboxBookRowModel: Identifiable, Equatable {
    let id: String
    let title: String
    let author: String?
    let coverPath: String?
    let coverHash: String?
    let durationMs: Int64

    var formattedDuration: String {
        DurationFormatting.hoursMinutes(ms: durationMs)
    }

    init(from item: InboxBookItem) {
        self.id = item.id
        self.title = item.title
        self.author = item.author
        self.coverPath = item.coverPath
        self.coverHash = item.coverHash
        self.durationMs = item.durationMs
    }
}

// MARK: - Scan issue row model

/// A folder the scanner walked but could not turn into a book.
///
/// A scan issue is the only place a failed import is visible at all: before these existed, a book
/// that failed produced a log line and nothing else — absent from the library with no trace the
/// user could see. So this is not decoration; it is the trace.
///
/// The reason → copy derivation lives here rather than in the view, matching where
/// `InboxBookRowModel.formattedDuration` puts the same kind of work: the view renders strings, it
/// does not compute them.
struct ScanIssueRowModel: Identifiable, Equatable {
    let id: String
    let rootRelPath: String
    let headline: String
    let fix: String
    let detail: String?

    init(from issue: ScanIssue) {
        self.id = issue.id
        self.rootRelPath = issue.rootRelPath
        self.headline = Self.headline(for: issue.reason)
        self.fix = Self.fix(for: issue.reason)
        self.detail = issue.detail
    }

    /// What went wrong, in the user's terms rather than the scanner's.
    private static func headline(for reason: ScanIssueReason) -> String {
        switch reason {
        case .noRecognizedAudio: return String(localized: "admin.inbox_issue_no_audio")
        case .fileUnreadable: return String(localized: "admin.inbox_issue_unreadable")
        case .metadataParseFailed: return String(localized: "admin.inbox_issue_metadata")
        case .titleInferenceFailed: return String(localized: "admin.inbox_issue_title")
        case .unknown: return String(localized: "admin.inbox_issue_unknown")
        }
    }

    /// What to do about it.
    ///
    /// Every reason has its own, because a notice the user cannot act on is just an apology — and
    /// if two reasons gave the same advice they did not need to be two reasons. Deliberately no
    /// `default` branch: a new reason must fail to compile here rather than quietly inherit
    /// someone else's remedy.
    private static func fix(for reason: ScanIssueReason) -> String {
        switch reason {
        case .noRecognizedAudio: return String(localized: "admin.inbox_issue_no_audio_fix")
        case .fileUnreadable: return String(localized: "admin.inbox_issue_unreadable_fix")
        case .metadataParseFailed: return String(localized: "admin.inbox_issue_metadata_fix")
        case .titleInferenceFailed: return String(localized: "admin.inbox_issue_title_fix")
        case .unknown: return String(localized: "admin.inbox_issue_unknown_fix")
        }
    }
}
