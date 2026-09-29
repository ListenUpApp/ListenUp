import SwiftUI
import Shared

/// Admin Collections list — browse, create, and delete shared book sets.
///
/// Layout is width-responsive (rule 12): the grid's column count flows from the available
/// width via `GridItem(.adaptive(minimum:))`, so it stays right at every point on the
/// continuum — full-screen iPad, 1/2 + 1/3 Split View, Stage Manager — not a fixed N-column
/// grid that crushes tiles in a narrow split view. Each tile shows a folder icon, the
/// collection name, the book count, and a globe badge for globally-accessible collections.
/// Book cover images are not available on the list state (only `bookCount` is present), so the
/// tile uses a folder SF Symbol instead of a `CoverStack`.
///
/// Collection creation is a sheet with a single name field; deletion is a swipe or
/// long-press context menu that fires `deleteCollection`.
struct AdminCollectionsView: View {
    @Environment(\.dependencies) private var deps

    @State private var observer: AdminCollectionsObserver?
    @State private var showingCreateSheet = false
    @State private var createName = ""
    @State private var pendingDeleteId: String?

    /// Width-driven columns: the count emerges from the available width. 160pt matches the
    /// tile's natural footprint (the prior compact layout was ~2 columns on an iPhone).
    private let gridColumns = [GridItem(.adaptive(minimum: 160), spacing: 12)]

    var body: some View {
        Group {
            if let observer {
                content(observer: observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "common.collections"))
        .navigationBarTitleDisplayMode(.large)
        .toolbar { addToolbarItem }
        .onAppear {
            if observer == nil {
                observer = AdminCollectionsObserver(viewModel: deps.createAdminCollectionsViewModel())
            }
        }
        .sheet(isPresented: $showingCreateSheet, onDismiss: { createName = "" }) {
            createSheet
        }
        .confirmationDialog(
            String(localized: "common.delete"),
            isPresented: Binding(
                get: { pendingDeleteId != nil },
                set: { if !$0 { pendingDeleteId = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let id = pendingDeleteId {
                Button(String(localized: "common.delete"), role: .destructive) {
                    observer?.deleteCollection(collectionId: id)
                    pendingDeleteId = nil
                }
            }
            Button(String(localized: "common.cancel"), role: .cancel) { pendingDeleteId = nil }
        }
    }

    // MARK: - Content

    @ViewBuilder
    private func content(observer: AdminCollectionsObserver) -> some View {
        switch observer.phase {
        case .loading:
            LoadingStateView()
        case .ready(let ready):
            readyBody(observer: observer, ready: ready)
        case .error(let message):
            errorBody(message: message, observer: observer)
        }
    }

    @ViewBuilder
    private func readyBody(observer: AdminCollectionsObserver, ready: AdminCollectionsReadyModel) -> some View {
        ScrollView {
            LazyVGrid(columns: gridColumns, spacing: 12) {
                ForEach(ready.collections) { collection in
                    NavigationLink(value: AdminCollectionDetailDestination(collectionId: collection.id)) {
                        CollectionTile(
                            collection: collection,
                            isDeleting: ready.deletingCollectionId == collection.id
                        )
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        // ⛔ The server refuses to delete its own collections, so Delete is not
                        // offered for one. A context menu is invisible until long-pressed, which
                        // is why the tile also wears a padlock — otherwise the only signal would
                        // be an option that silently is not there.
                        if !collection.isSystem {
                            Button(role: .destructive) {
                                pendingDeleteId = collection.id
                            } label: {
                                Label(String(localized: "common.delete"), systemImage: "trash")
                            }
                        }
                    }
                }

                // New Collection tile
                Button {
                    showingCreateSheet = true
                } label: {
                    NewCollectionTile()
                }
                .buttonStyle(.plain)
            }
            .padding(16)
        }
        .overlay {
            if ready.collections.isEmpty {
                emptyState(observer: observer)
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
        } message: { msg in
            Text(msg)
        }
    }

    @ViewBuilder
    private func errorBody(message: String, observer: AdminCollectionsObserver) -> some View {
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

    @ViewBuilder
    private func emptyState(observer: AdminCollectionsObserver) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "folder")
                .scaledFont(size: 44, relativeTo: .largeTitle)
                .foregroundStyle(.secondary)
            Text(String(localized: "common.collections"))
                .font(.headline)
            Text(String(localized: "admin.create_a_collection_to_organize"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button(String(localized: "admin.collection_new_collection")) {
                showingCreateSheet = true
            }
            .buttonStyle(.borderedProminent)
            .onBrandFillLabel()
            .padding(.top, 4)
        }
        .padding()
    }

    // MARK: - Create sheet

    private var createSheet: some View {
        NavigationStack {
            Form {
                AppTextField(
                    placeholder: String(localized: "admin.collection_name"),
                    text: $createName,
                    entry: .words,
                    label: String(localized: "admin.collection_name"),
                    icon: "folder.badge.plus"
                )
            }
            .navigationTitle(String(localized: "admin.collection_new_collection"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel")) {
                        showingCreateSheet = false
                        createName = ""
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "common.save")) {
                        observer?.createCollection(name: createName)
                        showingCreateSheet = false
                        createName = ""
                    }
                    .disabled(createName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private var addToolbarItem: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Button {
                showingCreateSheet = true
            } label: {
                Image(systemName: "plus")
            }
        }
    }
}

// MARK: - Collection tile

/// A single collection grid tile: folder icon, name, book count, access badge.
private struct CollectionTile: View {
    let collection: CollectionRowModel
    let isDeleting: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack(alignment: .topTrailing) {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.luFill)
                    .aspectRatio(1, contentMode: .fit)
                if isDeleting {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    Image(systemName: "folder.fill")
                        .font(.system(size: 36)) // decorative fixed size
                        .foregroundStyle(Color.luTint)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                if collection.isSystem {
                    Image(systemName: "lock.fill")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(8)
                        .accessibilityLabel(Text(String(localized: "admin.system_collection_locked")))
                }
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(collection.name)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)

                HStack(spacing: 4) {
                    Image(systemName: "person.2")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text("\(collection.bookCount)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .contentShape(Rectangle())
    }
}

// MARK: - New collection tile

private struct NewCollectionTile: View {
    var body: some View {
        VStack(spacing: 8) {
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(style: StrokeStyle(lineWidth: 1.5, dash: [6, 3]))
                    .foregroundStyle(.tertiary)
                    .aspectRatio(1, contentMode: .fit)
                Image(systemName: "plus")
                    .font(.system(size: 28, weight: .medium)) // decorative fixed size
                    .foregroundStyle(.secondary)
            }

            Text(String(localized: "admin.collection_new_collection"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
        .contentShape(Rectangle())
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        AdminCollectionsView()
    }
}
