import SwiftUI
import Shared

/// Categories — the genre hierarchy as an indented tree with create, rename, move, merge and delete,
/// for admins and for members with Curate library. Each control follows its permission: create,
/// rename and move need Edit metadata; merge, merge history and delete need Curate library.
///
/// The tree is a plain `List` of `GenreRowModel` values the observer has already flattened by
/// expansion state, indented by depth — no outline group, because expansion lives in the shared
/// ViewModel (so "Expand All" and a tap agree, and a rename never collapses what was open).
/// Per-row actions sit in a context menu, the way the collections tiles do; creating a top-level
/// genre is the toolbar's plus. Every mutation is a sheet or a confirmation, never inline.
struct AdminCategoriesView: View {
    @Environment(\.dependencies) private var deps
    @State private var observer: AdminCategoriesObserver?
    @State private var nameSheet: GenreNameSheetTarget?
    @State private var moveSource: GenrePickModel?
    @State private var mergeSource: GenrePickModel?
    @State private var pendingDelete: GenreRowModel?

    var body: some View {
        Group {
            if let observer {
                content(observer: observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "common.categories"))
        .navigationBarTitleDisplayMode(.large)
        .toolbar { toolbarItems }
        .onAppear {
            if observer == nil {
                observer = AdminCategoriesObserver(viewModel: deps.createAdminCategoriesViewModel())
            }
        }
        .sheet(item: $nameSheet) { target in
            GenreNameSheet(target: target) { name in
                switch target {
                case .create(let parentId, _):
                    observer?.createGenre(name: name, parentId: parentId)
                case .rename(let id, _):
                    observer?.renameGenre(id: id, name: name)
                }
            }
        }
        .sheet(item: $moveSource) { source in
            GenreMoveSheet(source: source, candidates: moveCandidates(for: source)) { newParentId in
                observer?.moveGenre(id: source.id, newParentId: newParentId)
            }
        }
        .sheet(
            item: Binding(get: { observer?.mergeHistory }, set: { if $0 == nil { observer?.closeMergeHistory() } })
        ) { open in
            GenreMergeHistorySheet(
                open: open,
                onUndo: { observer?.undoGenreMerge(receiptId: $0) },
                onRetry: { observer?.retryMergeHistory() },
                onDone: { observer?.closeMergeHistory() }
            )
        }
        .sheet(item: $mergeSource) { source in
            GenreMergeSheet(source: source, candidates: mergeCandidates(for: source)) { targetId in
                observer?.mergeGenres(source: source.id, target: targetId)
            }
        }
        .confirmationDialog(
            String(localized: "common.delete"),
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            titleVisibility: .visible,
            presenting: pendingDelete
        ) { row in
            Button(String(localized: "common.delete"), role: .destructive) {
                observer?.deleteGenre(id: row.id)
                pendingDelete = nil
            }
            Button(String(localized: "common.cancel"), role: .cancel) { pendingDelete = nil }
        } message: { row in
            Text(String(format: String(localized: "admin.confirm_delete_item"), row.name))
        }
    }

    // MARK: - Content

    @ViewBuilder
    private func content(observer: AdminCategoriesObserver) -> some View {
        switch observer.phase {
        case .loading:
            LoadingStateView()
        case .ready(let ready):
            readyBody(observer: observer, ready: ready)
        case .error(let message):
            errorBody(message: message)
        }
    }

    @ViewBuilder
    private func readyBody(observer: AdminCategoriesObserver, ready: AdminCategoriesReadyModel) -> some View {
        List {
            Section {
                ForEach(ready.rows) { row in
                    genreRow(row, observer: observer)
                        .contextMenu { rowMenu(row: row, ready: ready) }
                }
            } header: {
                Text(
                    String(
                        format: String(localized: "admin.categories_books_count"),
                        ready.genreCount,
                        ready.totalBookCount
                    )
                )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .textCase(nil)
            }
        }
        .listStyle(.plain)
        .overlay(alignment: .top) {
            if ready.isSaving {
                ProgressView().progressViewStyle(.linear)
            }
        }
        .overlay {
            if ready.rows.isEmpty {
                emptyState(canEdit: ready.canEditMetadata)
            }
        }
        .alert(
            String(localized: "common.something_went_wrong"),
            isPresented: Binding(
                get: { ready.error != nil },
                set: { if !$0 { observer.clearError() } }
            ),
            presenting: ready.error
        ) { _ in
            Button(String(localized: "common.ok")) { observer.clearError() }
        } message: { message in
            Text(message)
        }
    }

    /// Add Sub-genre, Rename, Merge into…, Merge history, Move to…, Delete — the same six, in the
    /// same order, as Compose's long-press menu. Each shows only to those allowed it: adding, renaming
    /// and moving need Edit metadata; merging, its history and deleting need Curate library.
    @ViewBuilder
    private func rowMenu(row: GenreRowModel, ready: AdminCategoriesReadyModel) -> some View {
        if ready.canEditMetadata {
            Button {
                nameSheet = .create(parentId: row.id, parentName: row.name)
            } label: {
                Label(String(localized: "admin.add_subgenre"), systemImage: "plus")
            }
            Button {
                nameSheet = .rename(id: row.id, currentName: row.name)
            } label: {
                Label(String(localized: "common.rename"), systemImage: "pencil")
            }
        }
        if ready.canCurateLibrary {
            Button {
                mergeSource = pick(for: row)
            } label: {
                Label(String(localized: "admin.merge_into"), systemImage: "arrow.triangle.merge")
            }
            Button {
                observer?.openMergeHistory(id: row.id)
            } label: {
                Label(String(localized: "merge_history.open"), systemImage: "clock.arrow.circlepath")
            }
        }
        if ready.canEditMetadata {
            Button {
                moveSource = pick(for: row)
            } label: {
                Label(String(localized: "admin.move_to"), systemImage: "arrow.right")
            }
        }
        if ready.canCurateLibrary {
            Button(role: .destructive) {
                pendingDelete = row
            } label: {
                Label(String(localized: "common.delete"), systemImage: "trash")
            }
        }
    }

    @ViewBuilder
    private func errorBody(message: String) -> some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle")
                .font(.largeTitle)
                .foregroundStyle(.secondary)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
    }

    private func emptyState(canEdit: Bool) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "tag")
                .scaledFont(size: 44, relativeTo: .largeTitle)
                .foregroundStyle(.secondary)
            Text(String(localized: "genre.no_genres_yet"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
            if canEdit {
                Button(String(localized: "admin.add_genre")) {
                    nameSheet = .create(parentId: nil, parentName: nil)
                }
                .buttonStyle(.borderedProminent)
                .onBrandFillLabel()
                .padding(.top, Spacing.xxs)
            }
        }
        .padding()
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private var toolbarItems: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            if case .ready(let ready) = observer?.phase, !ready.rows.isEmpty {
                Button {
                    if ready.allExpanded { observer?.collapseAll() } else { observer?.expandAll() }
                } label: {
                    Image(
                        systemName: ready.allExpanded ? "rectangle.compress.vertical" : "rectangle.expand.vertical"
                    )
                }
                .accessibilityLabel(
                    Text(String(localized: ready.allExpanded ? "admin.collapse_all" : "admin.expand_all"))
                )
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            if case .ready(let ready) = observer?.phase, ready.canEditMetadata {
                Button {
                    nameSheet = .create(parentId: nil, parentName: nil)
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel(Text(String(localized: "admin.add_genre")))
            }
        }
    }

    // MARK: - Picks

    private func pick(for row: GenreRowModel) -> GenrePickModel {
        GenrePickModel(id: row.id, name: row.name, path: row.path, bookCount: row.bookCount)
    }

    /// A parent row is a real `Button` whose value is its expansion state, so VoiceOver can find
    /// and operate it; a leaf does nothing on tap, so it isn't a control. HIG, Disclosure controls.
    @ViewBuilder
    private func genreRow(_ row: GenreRowModel, observer: AdminCategoriesObserver) -> some View {
        if let expansionState = row.expansionState {
            Button {
                observer.toggleExpanded(id: row.id)
            } label: {
                GenreRowView(row: row)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityValue(expansionState)
        } else {
            GenreRowView(row: row)
        }
    }

    private func moveCandidates(for source: GenrePickModel) -> [GenrePickModel] {
        guard case .ready(let ready) = observer?.phase else { return [] }
        return GenreTree.moveCandidates(all: ready.picks, source: source)
    }

    private func mergeCandidates(for source: GenrePickModel) -> [GenrePickModel] {
        guard case .ready(let ready) = observer?.phase else { return [] }
        return GenreTree.mergeCandidates(all: ready.picks, source: source)
    }
}

// MARK: - Row

/// One tree row: a chevron that only exists for parents, the genre icon (tinted at the root),
/// the name, and the book count when there is one. Indentation is the depth, nothing else.
private struct GenreRowView: View {
    let row: GenreRowModel

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .rotationEffect(.degrees(row.isExpanded ? 90 : 0))
                .opacity(row.hasChildren ? 1 : 0)
                .accessibilityHidden(true)
            Image(systemName: "tag")
                .font(.body)
                .foregroundStyle(row.depth == 0 ? Color.luTint : Color.secondary)
            Text(row.name)
                .font(row.depth == 0 ? .body : .subheadline)
                .lineLimit(1)
            Spacer(minLength: 8)
            if row.bookCount > 0 {
                Text("\(row.bookCount)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.leading, CGFloat(row.depth) * Spacing.l)
        .padding(.vertical, Spacing.xxs)
        .accessibilityElement(children: .combine)
    }
}

#Preview {
    NavigationStack {
        AdminCategoriesView()
    }
}
