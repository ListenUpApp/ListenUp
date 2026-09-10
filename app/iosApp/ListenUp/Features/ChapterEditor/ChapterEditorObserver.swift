import Foundation
import Shared
import SwiftUI

/// One boundary as the EDITOR consumes it — a native Swift value.
///
/// Distinct from the player's `ChapterRowModel`, which is the read surface: this one carries the
/// number the reader edits against, whether the boundary is pinned against drift, and a half-open
/// end rather than a duration. Folding the two together would put editor concerns on every
/// now-playing surface and a duration where this needs a boundary.
///
/// ⛔ Not the bridged `Chapter`. Every SwiftUI diff re-reads a bridged object's properties across
/// the Kotlin boundary, and this list is routinely three hundred rows long — the same re-bridging
/// that froze the library grid. The observer maps once, in `apply`, and the view never sees Kotlin.
///
/// `number` is the chapter's 1-based position in the **full** set, captured before any filtering.
/// Numbering the visible rows would relabel chapter 213 as chapter 1 the moment someone searched.
struct EditableChapterRow: Identifiable, Equatable {
    let id: String
    let number: Int
    let title: String
    let startMs: Int64
    let endMs: Int64
    let isLocked: Bool

    /// Whether `positionMs` falls inside this chapter — which boundary the listener is inside.
    func holds(_ positionMs: Int64) -> Bool { positionMs >= startMs && positionMs < endMs }
}

/// The guided drift flow, flattened.
struct DriftModel: Equatable {
    var firstAnchor: String?
    var secondAnchor: String?
    /// The summary when the pinned anchors produce a correction, else nil.
    var summary: String?
    /// Why the anchors cannot be used, else nil. Never set at the same time as `summary`.
    var refusal: String?
    var canApply: Bool
}

/// What the editor is showing, flattened from `ChapterEditorUiState`.
enum ChapterEditorPhase: Equatable {
    case loading
    case editing
    case failed(String)
}

/// Observes `ChapterEditorViewModel`, flattening `ChapterEditorUiState` into `@Observable`
/// properties and dispatching edits as method calls.
///
/// The ViewModel takes methods rather than one sealed event, so this observer's API is the
/// ViewModel's vocabulary unchanged — wrapping it in an iOS-only event type would be a second
/// vocabulary for the same operations.
///
/// `Saved` flips `didFinish` so the presenter can dismiss. `Invalid` is different in kind: nothing
/// left the device and a specific row needs fixing, so it lands in `problem` — inline, staying put
/// — rather than in the alert a failed save would use.
@Observable
@MainActor
final class ChapterEditorObserver {
    private(set) var phase: ChapterEditorPhase = .loading
    private(set) var bookTitle: String = ""
    private(set) var chapters: [EditableChapterRow] = []
    private(set) var selectedChapterId: String?
    private(set) var isDirty: Bool = false
    private(set) var canUndo: Bool = false
    private(set) var isSaving: Bool = false
    private(set) var changedElsewhere: Bool = false
    private(set) var drift: DriftModel?
    private(set) var didFinish: Bool = false

    /// A set refused before anything was sent, named by the row responsible. Cleared on the next
    /// save attempt, never on a timer: it names a row the reader still has to go and fix.
    private(set) var problem: String?

    /// True when the book has chapters to interpolate between — the drift flow's precondition.
    var canFixDrift: Bool { chapters.count > 1 && drift == nil }

    private let viewModel: ChapterEditorViewModel
    private let bridge = FlowBridge()

    init(viewModel: ChapterEditorViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.events) { [weak self] in self?.applyEvent($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    /// ⛔ The ViewModel's own `close()`, not just a cancelled bridge. Its KDoc names iOS as
    /// responsible for calling it: without it the `viewModelScope` outlives the screen and an
    /// in-flight save orphans.
    func close() {
        bridge.cancelAll()
        viewModel.close()
    }

    func select(_ chapterId: String?) { viewModel.select(chapterId: chapterId) }
    func nudge(_ chapterId: String, byMs deltaMs: Int64) { viewModel.nudge(chapterId: chapterId, deltaMs: deltaMs) }
    func snapToPlayhead(_ chapterId: String, atMs playheadMs: Int64) {
        viewModel.snapToPlayhead(chapterId: chapterId, playheadMs: playheadMs)
    }
    func retitle(_ chapterId: String, to title: String) { viewModel.retitle(chapterId: chapterId, title: title) }
    func remove(_ chapterId: String) { viewModel.remove(chapterId: chapterId) }
    func addAt(_ atMs: Int64, title: String) { viewModel.addAt(atMs: atMs, title: title) }
    func toggleLock(_ chapterId: String) { viewModel.toggleLock(chapterId: chapterId) }
    func beginDrift() { viewModel.beginDrift() }
    func pinAnchor(_ chapterId: String, atMs trueStartMs: Int64) {
        viewModel.pinAnchor(chapterId: chapterId, trueStartMs: trueStartMs)
    }
    func applyDrift() { viewModel.applyDrift() }
    func cancelDrift() { viewModel.cancelDrift() }
    func undo() { viewModel.undo() }
    func resetToSource() { viewModel.resetToSource() }

    func save() {
        problem = nil
        viewModel.save()
    }

    private func apply(_ state: ChapterEditorUiState) {
        switch state.sealedType() {
        case .loading:
            phase = .loading

        case .error(let type):
            phase = .failed(type.value.message)

        case .editing(let type):
            let editing = type.value
            phase = .editing
            bookTitle = editing.bookTitle
            selectedChapterId = editing.selectedChapterId
            isDirty = editing.isDirty
            canUndo = editing.canUndo
            isSaving = editing.isSaving
            changedElsewhere = editing.changedElsewhere
            chapters = rowModels(editing)
            drift = driftModel(editing)
        }
    }

    private func rowModels(_ editing: ChapterEditorUiStateEditing) -> [EditableChapterRow] {
        let locked = editing.lockedChapterIds
        return editing.chapters.enumerated().map { index, chapter in
            EditableChapterRow(
                id: chapter.id,
                number: index + 1,
                title: chapter.title,
                startMs: chapter.startTime,
                endMs: chapter.startTime + chapter.duration,
                isLocked: locked.contains(chapter.id)
            )
        }
    }

    /// ⛔ Takes the whole `Editing` state, not its `drift`. Swift Export nests `DriftState` inside
    /// `ChapterEditorUiState` and the only alias for it is `internal`, so the type cannot be named
    /// from app code at all — reading it off `editing` lets inference supply what cannot be written.
    private func driftModel(_ editing: ChapterEditorUiStateEditing) -> DriftModel? {
        guard let drift = editing.drift else { return nil }
        let chapters = editing.chapters
        var model = DriftModel(
            firstAnchor: nil,
            secondAnchor: nil,
            summary: nil,
            refusal: nil,
            canApply: false
        )
        if let proposal = drift.proposal {
            model.firstAnchor = anchorLabel(proposal.first, in: chapters)
            model.secondAnchor = proposal.second.map { anchorLabel($0, in: chapters) }
        }
        guard let preview = drift.preview else { return model }
        switch preview.sealedType() {
        case .ready(let type):
            let ready = type.value
            // A single anchor is the degenerate case — a constant shift — and quoting a spread of
            // zero would read as "this changes nothing" for a correction that moves every boundary.
            model.summary = ready.spreadMs == 0
                ? String(
                    format: String(localized: "chapter_editor.drift_summary_single"),
                    ready.affectedCount,
                    ChapterTimeFormat.shared.offset(ms: ready.firstOffsetMs)
                )
                : String(
                    format: String(localized: "chapter_editor.drift_summary"),
                    ready.affectedCount,
                    ChapterTimeFormat.shared.offset(ms: ready.spreadMs)
                )
            model.canApply = true

        case .refused(let type):
            model.refusal = switch type.value.reason {
            case .UnusableAnchors: String(localized: "chapter_editor.drift_refused_anchors")
            case .InvertedAnchors: String(localized: "chapter_editor.drift_refused_inverted")
            }
        }
        return model
    }

    /// Names an anchor by the number the reader can see, and the time they pinned it at.
    private func anchorLabel(_ anchor: ChapterAnchor, in chapters: [Chapter]) -> String {
        let position = chapters.firstIndex { $0.id == anchor.chapterId }
        return String(
            format: String(localized: "chapter_editor.drift_anchor_at"),
            position.map { $0 + 1 } ?? 0,
            ChapterTimeFormat.shared.precise(ms: anchor.trueStartMs)
        )
    }

    private func applyEvent(_ event: ChapterEditorEvent) {
        switch event.sealedType() {
        case .saved:
            didFinish = true

        // SaveFailed already reaches the reader through the shared error surface; repeating it
        // here would show the same failure twice.
        case .saveFailed:
            break

        case .invalid(let type):
            problem = ChapterProblemText.message(for: type.value.problems, chapters: chapters)
        }
    }
}
