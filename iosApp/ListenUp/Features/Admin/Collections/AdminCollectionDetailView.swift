import SwiftUI
import Shared

/// Admin Collection Detail — edit a collection's name, manage its books and member shares.
///
/// Layout is width-responsive (rule 12): on compact, all sections are in a single column;
/// on regular (iPad), the books grid occupies the leading column and the name + members
/// panel occupies the trailing column.
///
/// Book covers use `BookCoverImage` in a width-driven adaptive grid.
/// Members are listed in a `FieldGroup` with initials avatars and a trailing "Add" button
/// that opens the add-member sheet. Destructive actions (remove book, revoke share) go
/// through confirmation dialogs.
struct AdminCollectionDetailView: View {
    let collectionId: String

    @Environment(\.dependencies) private var deps
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var observer: AdminCollectionDetailObserver?
    @State private var pendingRemoveBookId: String?
    @State private var pendingRevokeUserId: String?

    private var isRegularWidth: Bool { horizontalSizeClass == .regular }

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
        ScrollView {
            if isRegularWidth {
                HStack(alignment: .top, spacing: 28) {
                    booksSection(observer: observer, ready: ready)
                        .frame(maxWidth: .infinity, alignment: .top)
                    VStack(spacing: 26) {
                        nameSection(observer: observer, ready: ready)
                        membersSection(observer: observer, ready: ready)
                    }
                    .frame(maxWidth: .infinity, alignment: .top)
                }
                .padding(.horizontal, 32)
                .padding(.vertical, 16)
            } else {
                VStack(spacing: 26) {
                    nameSection(observer: observer, ready: ready)
                    booksSection(observer: observer, ready: ready)
                    membersSection(observer: observer, ready: ready)
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
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
        .confirmationDialog(
            String(localized: "common.revoke"),
            isPresented: Binding(
                get: { pendingRevokeUserId != nil },
                set: { if !$0 { pendingRevokeUserId = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let userId = pendingRevokeUserId {
                Button(String(localized: "common.revoke"), role: .destructive) {
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
    }

    // MARK: - Name section

    @ViewBuilder
    private func nameSection(observer: AdminCollectionDetailObserver, ready: AdminCollectionDetailReadyModel) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            AdminSectionHeader(String(localized: "admin.collection_details"))
            AppTextField(
                placeholder: String(localized: "admin.collection_name"),
                text: Binding(
                    get: { ready.editedName },
                    set: { observer.updateName($0) }
                ),
                label: String(localized: "admin.collection_name"),
                icon: "folder"
            )
            .fieldCard()
            if ready.isDirty {
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
                .tint(Color.luTint)
                .disabled(ready.isSaving)
                .padding(.top, 8)
            }
        }
    }

    // MARK: - Books section

    @ViewBuilder
    private func booksSection(observer: AdminCollectionDetailObserver, ready: AdminCollectionDetailReadyModel) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            AdminSectionHeader(String(localized: "admin.books_in_collection"))
            if ready.books.isEmpty {
                Text(String(localized: "admin.create_a_collection_to_organize"))
                    .font(.subheadline)
                    .foregroundStyle(Color.luLabel2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .fieldCard()
            } else {
                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: 80), spacing: 8)],
                    spacing: 8
                ) {
                    ForEach(ready.books) { book in
                        bookCoverCell(observer: observer, book: book, isRemoving: ready.removingBookId == book.id)
                    }
                }
                .padding(8)
                .background(Color.luSurface2)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
        }
    }

    @ViewBuilder
    private func bookCoverCell(
        observer: AdminCollectionDetailObserver,
        book: CollectionBookRowModel,
        isRemoving: Bool
    ) -> some View {
        ZStack(alignment: .topTrailing) {
            BookCoverImage(bookId: book.id, coverPath: book.coverPath, coverHash: book.coverHash)
                .frame(width: 80, height: 80)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                .opacity(isRemoving ? 0.5 : 1)

            if isRemoving {
                ProgressView()
                    .frame(width: 80, height: 80)
            } else {
                Button {
                    pendingRemoveBookId = book.id
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 18))
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, Color.black.opacity(0.6))
                }
                .padding(2)
            }
        }
        .accessibilityLabel(book.title)
    }

    // MARK: - Members section

    @ViewBuilder
    private func membersSection(observer: AdminCollectionDetailObserver, ready: AdminCollectionDetailReadyModel) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            AdminSectionHeader(String(localized: "common.members")) {
                Button {
                    observer.loadUsersForSharing()
                    observer.showAddMemberSheet()
                } label: {
                    Label(String(localized: "common.add"), systemImage: "plus")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Color.luTint)
                }
            }

            if ready.shares.isEmpty {
                Text(String(localized: "admin.add_members_to_share_this"))
                    .font(.subheadline)
                    .foregroundStyle(Color.luLabel2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .fieldCard()
            } else {
                FieldGroup(ready.shares) { share in
                    memberRow(observer: observer, share: share, isRevoking: ready.removingShareUserId == share.userId)
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
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
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
                    .font(.system(size: 40))
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
