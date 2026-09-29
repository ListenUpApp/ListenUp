import SwiftUI
import Shared

/// Admin Collection Detail — edit a collection's name, manage its books and member shares.
///
/// One grouped `Form` in a readable column at every width (rule 12, via `readableListWidth`).
/// Book covers use `BookCoverImage` in a width-driven adaptive grid inside the Books section.
/// Members are list rows with initials avatars; the section header's "Add" button opens the
/// add-member sheet. Destructive actions (remove book, revoke share) go
/// through confirmation dialogs.
struct AdminCollectionDetailView: View {
    let collectionId: String

    @Environment(\.dependencies) private var deps

    @State private var observer: AdminCollectionDetailObserver?
    @State private var pendingRemoveBookId: String?
    @State private var pendingRevokeUserId: String?

    var body: some View {
        Group {
            if let observer {
                content(observer: observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationBarTitleDisplayMode(.large)
        .onAppear {
            if observer == nil {
                observer = AdminCollectionDetailObserver(
                    viewModel: deps.createAdminCollectionDetailViewModel(collectionId: collectionId)
                )
            }
        }
    }

    // MARK: - Content

    @ViewBuilder
    private func content(observer: AdminCollectionDetailObserver) -> some View {
        switch observer.phase {
        case .loading:
            LoadingStateView()
                .navigationTitle("")
        case .ready(let ready):
            readyBody(observer: observer, ready: ready)
                .navigationTitle(ready.collectionName)
        case .error(let message):
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
            .navigationTitle("")
        }
    }

    @ViewBuilder
    private func readyBody(observer: AdminCollectionDetailObserver, ready: AdminCollectionDetailReadyModel) -> some View {
        // One grouped `Form` at every width: a readable column on iPad rather than two hand-drawn
        // panes. HIG, Lists and tables.
        Form {
            nameSection(observer: observer, ready: ready)
            booksSection(observer: observer, ready: ready)
            membersSection(observer: observer, ready: ready)
        }
        .readableListWidth(720)
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
        .confirmationDialog(
            String(localized: "common.delete"),
            isPresented: Binding(
                get: { pendingRemoveBookId != nil },
                set: { if !$0 { pendingRemoveBookId = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let bookId = pendingRemoveBookId {
                Button(String(localized: "common.delete"), role: .destructive) {
                    observer.removeBook(bookId: bookId)
                    pendingRemoveBookId = nil
                }
            }
            Button(String(localized: "common.cancel"), role: .cancel) { pendingRemoveBookId = nil }
        }
        // "Remove access" says what happens to the member in plain words; "Revoke" is jargon.
        .confirmationDialog(
            String(localized: "admin.remove_access"),
            isPresented: Binding(
                get: { pendingRevokeUserId != nil },
                set: { if !$0 { pendingRevokeUserId = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let userId = pendingRevokeUserId {
                Button(String(localized: "admin.remove_access"), role: .destructive) {
                    observer.revokeShare(userId: userId)
                    pendingRevokeUserId = nil
                }
            }
            Button(String(localized: "common.cancel"), role: .cancel) { pendingRevokeUserId = nil }
        }
        .sheet(
            isPresented: Binding(
                get: { ready.showAddMemberSheet },
                set: { if !$0 { observer.hideAddMemberSheet() } }
            )
        ) {
            addMemberSheet(observer: observer, ready: ready)
        }
        .sheet(
            isPresented: Binding(
                get: { ready.showAddBooks },
                set: { if !$0 { observer.closeAddBooks() } }
            )
        ) {
            addBooksSheet(observer: observer, ready: ready)
        }
    }

    // MARK: - Name section

    @ViewBuilder
    private func nameSection(observer: AdminCollectionDetailObserver, ready: AdminCollectionDetailReadyModel) -> some View {
        Section {
            AppTextField(
                placeholder: String(localized: "admin.collection_name"),
                text: Binding(
                    get: { ready.editedName },
                    set: { observer.updateName($0) }
                ),
                entry: .words,
                label: String(localized: "admin.collection_name"),
                icon: "folder"
            )
            .disabled(ready.isSystem)
            if ready.isDirty && !ready.isSystem {
                Button {
                    observer.saveName()
                } label: {
                    if ready.isSaving {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                    } else {
                        Text(String(localized: "common.save"))
                            .frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.borderedProminent)
                .onBrandFillLabel()
                .disabled(ready.isSaving)
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
        } header: {
            Text(String(localized: "admin.collection_details"))
        } footer: {
            // ⛔ Not merely un-saveable: a field the server will refuse to change must be
            // unreachable, and must say why. Rendering it editable and rejecting the save
            // afterwards teaches the reader that the app lies about what it will accept.
            if ready.isSystem {
                Text(String(localized: "admin.system_collection_locked"))
            }
        }
    }

    // MARK: - Books section

    @ViewBuilder
    private func booksSection(observer: AdminCollectionDetailObserver, ready: AdminCollectionDetailReadyModel) -> some View {
        Section {
            if ready.books.isEmpty {
                Text(String(localized: "admin.no_books_in_this_collection"))
                    .foregroundStyle(Color.luLabel2)
            } else {
                // The covers are a width-driven grid inside one row: a collection of artwork, not a
                // list of text (HIG, Collections).
                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: 80), spacing: 8)],
                    spacing: 8
                ) {
                    ForEach(ready.books) { book in
                        bookCoverCell(
                            observer: observer,
                            book: book,
                            isRemoving: ready.removingBookId == book.id,
                            canRemove: !ready.isSystem
                        )
                    }
                }
                .padding(.vertical, 8)
            }
        } header: {
            AdminSectionHeader(String(localized: "admin.books_in_collection")) {
                // The server owns what is in a system collection, so there is nothing to add.
                if !ready.isSystem {
                    Button {
                        observer.openAddBooks()
                    } label: {
                        Label(String(localized: "admin.add_books"), systemImage: "plus")
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func bookCoverCell(
        observer: AdminCollectionDetailObserver,
        book: CollectionBookRowModel,
        isRemoving: Bool,
        canRemove: Bool
    ) -> some View {
        ZStack(alignment: .topTrailing) {
            BookCoverImage(bookId: book.id, coverPath: book.coverPath, coverHash: book.coverHash)
                .frame(width: 80, height: 80)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                .opacity(isRemoving ? 0.5 : 1)

            if isRemoving {
                ProgressView()
                    .frame(width: 80, height: 80)
            } else if canRemove {
                Button {
                    pendingRemoveBookId = book.id
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 18)) // decorative fixed size
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, Color.black.opacity(0.6))
                        .minimumTapTarget(visualSize: 22)
                }
                .accessibilityLabel(String(format: String(localized: "common.remove_name"), book.title))
                .padding(2)
            }
        }
        .accessibilityLabel(book.title)
    }

    // MARK: - Members section

    @ViewBuilder
    private func membersSection(observer: AdminCollectionDetailObserver, ready: AdminCollectionDetailReadyModel) -> some View {
        Section {
            if ready.shares.isEmpty {
                Text(String(localized: "admin.add_members_to_share_this"))
                    .foregroundStyle(Color.luLabel2)
            } else {
                ForEach(ready.shares) { share in
                    let isRevoking = ready.removingShareUserId == share.userId
                    memberRow(observer: observer, share: share, isRevoking: isRevoking)
                        // A shortcut beside the row's visible remove button (HIG, Gestures).
                        .swipeActions {
                            if !isRevoking {
                                Button(String(localized: "admin.remove_access"), role: .destructive) {
                                    pendingRevokeUserId = share.userId
                                }
                            }
                        }
                }
            }
        } header: {
            AdminSectionHeader(String(localized: "common.members")) {
                Button {
                    observer.loadUsersForSharing()
                    observer.showAddMemberSheet()
                } label: {
                    Label(String(localized: "common.add"), systemImage: "plus")
                }
            }
        }
    }

    @ViewBuilder
    private func memberRow(
        observer: AdminCollectionDetailObserver,
        share: CollectionShareRowModel,
        isRevoking: Bool
    ) -> some View {
        HStack(spacing: 12) {
            InitialsAvatar(initials: initials(for: share.displayName), size: 36)

            VStack(alignment: .leading, spacing: 1) {
                Text(share.displayName)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.primary)
                Text(share.permission.capitalized)
                    .font(.caption)
                    .foregroundStyle(Color.luLabel2)
            }

            Spacer()

            if isRevoking {
                ProgressView()
            } else {
                Button {
                    pendingRevokeUserId = share.userId
                } label: {
                    Image(systemName: "xmark.circle")
                        .foregroundStyle(Color.luLabel3)
                        .frame(width: TapTarget.minimum, height: TapTarget.minimum)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                // Says what it does, not what it looks like — VoiceOver would otherwise read the
                // glyph's own name (HIG, VoiceOver).
                .accessibilityLabel(String(localized: "admin.remove_access"))
            }
        }
    }

    // MARK: - Add books sheet

    @ViewBuilder
    private func addBooksSheet(observer: AdminCollectionDetailObserver, ready: AdminCollectionDetailReadyModel) -> some View {
        NavigationStack {
            Group {
                if ready.isSearchingBooks {
                    LoadingStateView()
                } else if ready.bookResults.isEmpty {
                    ContentUnavailableView(
                        String(localized: "admin.add_books"),
                        systemImage: "text.book.closed",
                        description: Text(String(localized: "admin.add_books_search_placeholder"))
                    )
                } else {
                    List(ready.bookResults) { book in
                        Button {
                            observer.addBookFromSearch(bookId: book.id)
                        } label: {
                            VStack(alignment: .leading, spacing: 1) {
                                Text(book.title)
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(.primary)
                                if let author = book.author, !author.isEmpty {
                                    Text(author)
                                        .font(.caption)
                                        .foregroundStyle(Color.luLabel2)
                                }
                            }
                        }
                    }
                }
            }
            .searchable(
                text: Binding(get: { ready.bookQuery }, set: { observer.onBookQueryChange($0) }),
                placement: .navigationBarDrawer(displayMode: .always),
                prompt: String(localized: "admin.add_books_search_placeholder")
            )
            .background(Color.luSurface)
            .navigationTitle(String(localized: "admin.add_books"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "common.done")) { observer.closeAddBooks() }
                }
            }
        }
    }

    // MARK: - Add member sheet

    @ViewBuilder
    private func addMemberSheet(observer: AdminCollectionDetailObserver, ready: AdminCollectionDetailReadyModel) -> some View {
        NavigationStack {
            addMemberSheetBody(observer: observer, ready: ready)
                .background(Color.luSurface)
                .navigationTitle(String(localized: "admin.add_members_to_share_this"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(String(localized: "common.cancel")) {
                            observer.hideAddMemberSheet()
                        }
                    }
                }
        }
    }

    @ViewBuilder
    private func addMemberSheetBody(observer: AdminCollectionDetailObserver, ready: AdminCollectionDetailReadyModel) -> some View {
        if ready.isLoadingUsers {
            LoadingStateView()
        } else if ready.availableUsers.isEmpty {
            VStack(spacing: 12) {
                Image(systemName: "person.2.slash")
                    .scaledFont(size: 40, relativeTo: .largeTitle)
                    .foregroundStyle(Color.luLabel2)
                Text(String(localized: "admin.all_users_are_already_members"))
                    .font(.subheadline)
                    .foregroundStyle(Color.luLabel2)
                    .multilineTextAlignment(.center)
            }
            .padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List(ready.availableUsers) { user in
                Button {
                    observer.shareWithUser(userId: user.id)
                    observer.hideAddMemberSheet()
                } label: {
                    HStack(spacing: 12) {
                        InitialsAvatar(initials: initials(for: user.displayName), size: 36)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(user.displayName)
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(.primary)
                            Text(user.email)
                                .font(.caption)
                                .foregroundStyle(Color.luLabel2)
                        }
                        Spacer()
                        if ready.isSharing {
                            ProgressView()
                        }
                    }
                }
                .buttonStyle(.plain)
            }
            .listStyle(.plain)
        }
    }

    // MARK: - Helpers

    private func initials(for name: String) -> String {
        let parts = name.split(separator: " ").prefix(2)
        let result = parts.compactMap { $0.first.map(String.init) }.joined()
        return result.isEmpty ? "?" : result.uppercased()
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        AdminCollectionDetailView(collectionId: "preview-id")
    }
}
