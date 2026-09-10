import Shared
import SwiftUI

/// The chapter editor — every boundary in a book, and everything that can be done to one.
///
/// **This is the list, not the timeline.** Android pairs the list with a minimap and a detail lane;
/// the spec calls those "optional spatial sugar" and requires the list alone to be sufficient. So
/// every operation is reachable from a row — nudge either way, take the playhead, pin against
/// drift, rename, remove — and the lane is not on this screen.
///
/// Two things it is careful about. The playhead is offered only when the book being edited is the
/// one actually loaded in the player: a position borrowed from a different book would write a
/// number from somewhere else entirely. And a dirty draft is never dropped silently — this screen
/// holds the only copy of the reader's work until they save, which is also why it is a
/// `fullScreenCover` at the call site rather than a sheet: a sheet's drag-to-dismiss would leave
/// the draft on the floor without asking.
struct ChapterEditorView: View {
    let bookId: String

    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss
    @State private var observer: ChapterEditorObserver?
    @State private var player: PlayerCoordinator?
    @State private var query: String = ""
    @State private var pendingDiscard = false
    @State private var renaming: EditableChapterRow?
    @State private var deleting: EditableChapterRow?
    @State private var renameText: String = ""

    var body: some View {
        NavigationStack {
            Group {
                if let observer {
                    content(observer)
                } else {
                    LoadingStateView()
                }
            }
            .navigationTitle(String(localized: "chapter_editor.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbar }
        }
        .task(id: bookId) {
            guard observer == nil else { return }
            player = deps.playerCoordinator
            observer = ChapterEditorObserver(viewModel: deps.createChapterEditorViewModel(bookId: bookId))
        }
        // ⛔ The ViewModel's scope outlives this view otherwise, and an in-flight save orphans.
        .onDisappear { observer?.close() }
        .onChange(of: observer?.didFinish ?? false) { _, finished in
            if finished { dismiss() }
        }
        .confirmationDialog(
            String(localized: "chapter_editor.discard_title"),
            isPresented: $pendingDiscard,
            titleVisibility: .visible
        ) {
            Button(String(localized: "common.discard"), role: .destructive) {
                observer?.resetToSource()
                dismiss()
            }
            Button(String(localized: "common.cancel"), role: .cancel) {}
        } message: {
            Text(String(localized: "chapter_editor.discard_body"))
        }
        .alert(String(localized: "chapter_editor.rename_title"), isPresented: renameBinding) {
            TextField(String(localized: "chapter_editor.rename_label"), text: $renameText)
            Button(String(localized: "common.cancel"), role: .cancel) { renaming = nil }
            Button(String(localized: "common.save")) {
                if let row = renaming { observer?.retitle(row.id, to: renameText) }
                renaming = nil
            }
            .disabled(renameText.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .confirmationDialog(
            String(localized: "chapter_editor.delete_title"),
            isPresented: deleteBinding,
            titleVisibility: .visible
        ) {
            Button(String(localized: "common.delete"), role: .destructive) {
                if let row = deleting { observer?.remove(row.id) }
                deleting = nil
            }
            Button(String(localized: "common.cancel"), role: .cancel) { deleting = nil }
        } message: {
            Text(String(localized: "chapter_editor.delete_body"))
        }
    }

    // MARK: - Content

    @ViewBuilder
    private func content(_ observer: ChapterEditorObserver) -> some View {
        switch observer.phase {
        case .loading:
            LoadingStateView()

        case .failed(let message):
            ContentUnavailableView {
                Label(String(localized: "chapter_editor.title"), systemImage: "list.bullet.indent")
            } description: {
                Text(message)
            }

        case .editing:
            editing(observer)
        }
    }

    @ViewBuilder
    private func editing(_ observer: ChapterEditorObserver) -> some View {
        List {
            if observer.changedElsewhere { Section { changedElsewhereNotice } }
            if let problem = observer.problem { Section { problemNotice(problem) } }
            if let drift = observer.drift { Section { driftSection(observer, drift: drift) } }
            chapterSection(observer)
        }
        .listStyle(.insetGrouped)
        .searchable(text: $query, prompt: Text(String(localized: "chapter_editor.jump_to_title")))
        .safeAreaInset(edge: .bottom) { addAtPlayheadBar(observer) }
    }

    private var changedElsewhereNotice: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(String(localized: "chapter_editor.changed_elsewhere_title"))
                .font(.subheadline.weight(.semibold))
            Text(String(localized: "chapter_editor.changed_elsewhere_body"))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    /// ⛔ Inline and staying put, not a toast. A refused save means nothing left the device and a
    /// specific row needs fixing — a message that fades takes the row number with it.
    private func problemNotice(_ problem: String) -> some View {
        Label(problem, systemImage: "exclamationmark.triangle.fill")
            .font(.subheadline)
            .foregroundStyle(Color.listenUpOrange)
    }

    private func driftSection(_ observer: ChapterEditorObserver, drift: DriftModel) -> some View {
        DriftPanel(
            drift: drift,
            canPin: observer.selectedChapterId != nil && playheadMs != nil,
            needsSelection: observer.selectedChapterId == nil,
            onPin: {
                if let id = observer.selectedChapterId, let at = playheadMs {
                    observer.pinAnchor(id, atMs: at)
                }
            },
            onApply: { observer.applyDrift() },
            onCancel: { observer.cancelDrift() }
        )
    }

    @ViewBuilder
    private func chapterSection(_ observer: ChapterEditorObserver) -> some View {
        let rows = filtered(observer.chapters)
        if observer.chapters.isEmpty {
            Section { emptyState(observer) }
        } else if rows.isEmpty {
            Section {
                Text(String(format: String(localized: "chapter_editor.no_matches"), query))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        } else {
            Section {
                ForEach(rows) { row in chapterRow(observer, row: row) }
            } header: {
                Text(String(
                    format: String(localized: "chapter_editor.subtitle"),
                    observer.bookTitle,
                    observer.chapters.count
                ))
            }
        }
    }

    private func chapterRow(_ observer: ChapterEditorObserver, row: EditableChapterRow) -> some View {
        ChapterEditRow(
            row: row,
            isSelected: row.id == observer.selectedChapterId,
            isPlaying: playheadMs.map { row.holds($0) } ?? false,
            playheadMs: playheadMs,
            onSelect: { observer.select(row.id) },
            onNudge: { observer.nudge(row.id, byMs: $0) },
            onSnapToPlayhead: { at in observer.snapToPlayhead(row.id, atMs: at) },
            onToggleLock: { observer.toggleLock(row.id) },
            onRename: {
                renameText = row.title
                renaming = row
            },
            onDelete: { deleting = row }
        )
    }

    @ViewBuilder
    private func emptyState(_ observer: ChapterEditorObserver) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(String(localized: "chapter_editor.empty_title"))
                .font(.headline)
            Text(String(localized: "chapter_editor.empty_body"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
            if let at = playheadMs {
                Button(String(localized: "chapter_editor.empty_add_first")) {
                    observer.addAt(at, title: String(localized: "chapter_editor.new_chapter_title"))
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(.vertical, 6)
    }

    /// ⛔ Absent, not disabled, when there is no playhead for THIS book — see the type's note.
    @ViewBuilder
    private func addAtPlayheadBar(_ observer: ChapterEditorObserver) -> some View {
        if let at = playheadMs, !observer.chapters.isEmpty {
            Button {
                observer.addAt(at, title: String(localized: "chapter_editor.new_chapter_title"))
            } label: {
                Label(String(localized: "chapter_editor.add_at_playhead"), systemImage: "plus")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .padding()
            .background(.bar)
        }
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button(String(localized: "common.cancel")) {
                if observer?.isDirty == true { pendingDiscard = true } else { dismiss() }
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button {
                observer?.beginDrift()
            } label: {
                Label(String(localized: "chapter_editor.drift_title"), systemImage: "waveform.path.ecg")
            }
            .disabled(observer?.canFixDrift != true)
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button {
                observer?.undo()
            } label: {
                Label(String(localized: "chapter_editor.undo"), systemImage: "arrow.uturn.backward")
            }
            .disabled(observer?.canUndo != true)
        }
        ToolbarItem(placement: .topBarTrailing) {
            // Invariant: while saving the action is REPLACED by a spinner, not merely disabled —
            // the same load-bearing guard against double-submit `EditSheetScaffold` documents.
            if observer?.isSaving == true {
                ProgressView()
            } else {
                Button(String(localized: "chapter_editor.done")) { observer?.save() }
                    .fontWeight(.semibold)
                    .disabled(observer?.isDirty != true)
            }
        }
    }

    // MARK: - Helpers

    /// The playhead, but only when the book being edited is the one loaded in the player.
    private var playheadMs: Int64? {
        guard let player, player.currentBookId == bookId else { return nil }
        return player.bookPositionMs
    }

    /// ⛔ Filters rows that were already numbered against the full set — never the other way round.
    private func filtered(_ rows: [EditableChapterRow]) -> [EditableChapterRow] {
        ChapterListFilter.matching(rows, query: query)
    }

    private var renameBinding: Binding<Bool> {
        Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })
    }

    private var deleteBinding: Binding<Bool> {
        Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })
    }
}
