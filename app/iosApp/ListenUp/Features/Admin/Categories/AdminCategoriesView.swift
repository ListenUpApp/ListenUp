import SwiftUI
import Shared

/// Admin Categories — the genre hierarchy as an indented tree with create, rename, move, merge and
/// delete. The same screen Android and the web have; iOS had none.
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
                    GenreRowView(row: row)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            if row.hasChildren { observer.toggleExpanded(id: row.id) }
                        }
                        .contextMenu { rowMenu(row: row) }
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
                    .foregroundStyle(Color.luLabel2)
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
                emptyState
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

    /// Add Sub-genre, Rename, Merge into…, Move to…, Delete — the same five, in the same order,
    /// as Compose's long-press menu.
    @ViewBuilder
    private func rowMenu(row: GenreRowModel) -> some View {
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
        Button {
            mergeSource = pick(for: row)
        } label: {
            Label(String(localized: "admin.merge_into"), systemImage: "arrow.triangle.merge")
        }
        Button {
            moveSource = pick(for: row)
        } label: {
            Label(String(localized: "admin.move_to"), systemImage: "arrow.right")
        }
        Button(role: .destructive) {
            pendingDelete = row
        } label: {
            Label(String(localized: "common.delete"), systemImage: "trash")
        }
    }

    @ViewBuilder
    private func errorBody(message: String) -> some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle")
                .font(.largeTitle)
                .foregroundStyle(Color.luLabel2)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(Color.luLabel2)
                .multilineTextAlignment(.center)
        }
        .padding()
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "tag")
                .font(.system(size: 44)) // decorative fixed size
                .foregroundStyle(Color.luLabel2)
            Text(String(localized: "genre.no_genres_yet"))
                .font(.subheadline)
                .foregroundStyle(Color.luLabel2)
            Button(String(localized: "admin.add_genre")) {
                nameSheet = .create(parentId: nil, parentName: nil)
            }
            .buttonStyle(.borderedProminent)
            .tint(Color.luTint)
            .padding(.top, 4)
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
            Button {
                nameSheet = .create(parentId: nil, parentName: nil)
            } label: {
                Image(systemName: "plus")
            }
            .accessibilityLabel(Text(String(localized: "admin.add_genre")))
        }
    }

    // MARK: - Picks

    private func pick(for row: GenreRowModel) -> GenrePickModel {
        GenrePickModel(id: row.id, name: row.name, path: row.path, bookCount: row.bookCount)
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
                .foregroundStyle(Color.luLabel2)
                .rotationEffect(.degrees(row.isExpanded ? 90 : 0))
                .opacity(row.hasChildren ? 1 : 0)
                .accessibilityHidden(true)
            Image(systemName: "tag")
                .font(.body)
                .foregroundStyle(row.depth == 0 ? Color.luTint : Color.luLabel2)
            Text(row.name)
                .font(row.depth == 0 ? .body : .subheadline)
                .lineLimit(1)
            Spacer(minLength: 8)
            if row.bookCount > 0 {
                Text("\(row.bookCount)")
                    .font(.caption)
                    .foregroundStyle(Color.luLabel2)
            }
        }
        .padding(.leading, CGFloat(row.depth) * 20)
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityHint(
            row.hasChildren
                ? Text(String(localized: row.isExpanded ? "common.collapse" : "common.expand"))
                : Text("")
        )
    }
}

#Preview {
    NavigationStack {
        AdminCategoriesView()
    }
}
