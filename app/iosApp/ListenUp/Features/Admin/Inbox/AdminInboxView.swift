import SwiftUI
import Shared

/// Admin inbox — freshly-scanned books awaiting triage before release into the library.
///
/// Layout is width-responsive (iosApp rule 12): compact width = a system inset-grouped `List`
/// (scan issues, then the held books — each a lazily built row); regular width (iPad, wide Split
/// View) = an adaptive multi-column grid of book cards.
/// Selection mode: tap a row to toggle; select-all / release actions appear in the header.
/// Release confirmation is a native alert. Transient errors surface as an alert.
/// A release confirms itself: the books leave the inbox, with a success haptic and the count
/// spoken to VoiceOver.
///
/// SSE updates flow through the shared VM into the observer — no extra wiring here.
struct AdminInboxView: View {
    @Environment(\.dependencies) private var deps
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var observer: AdminInboxObserver?
    @State private var showingReleaseConfirm = false
    /// Bumped once per landed release, to fire the success haptic.
    @State private var releases = 0
    /// The inbox book currently being edited in the BookEdit sheet (metadata + admin collections),
    /// so an admin can review and assign collections before releasing. `nil` when no sheet is open.
    @State private var editingBook: InboxEditTarget?
    /// The inbox book currently being matched against Audible metadata. `nil` when no sheet is open.
    @State private var metadataBook: InboxMetadataTarget?

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
        .navigationTitle(String(localized: "common.inbox"))
        .navigationBarTitleDisplayMode(.large)
        .toolbar { selectToolbarItem }
        .sheet(item: $editingBook) { target in
            BookEditView(bookId: target.id)
        }
        .sheet(item: $metadataBook) { target in
            MetadataMatchView(bookId: target.id, title: target.title, author: target.author, asin: nil)
        }
        .onAppear {
            if observer == nil {
                observer = AdminInboxObserver(viewModel: deps.createAdminInboxViewModel())
            }
        }
    }

    // MARK: - Content routing

    @ViewBuilder
    private func content(observer: AdminInboxObserver) -> some View {
        switch observer.phase {
        case .loading:
            LoadingStateView()
        case .ready(let ready):
            readyBody(observer: observer, ready: ready)
                .alert(
                    String(localized: "common.something_went_wrong"),
                    isPresented: errorPresented(ready: ready),
                    actions: {
                        Button(String(localized: "common.ok"), role: .cancel) { observer.clearError() }
                    },
                    message: {
                        if let message = ready.error { Text(message) }
                    }
                )
                .alert(
                    String(format: String(localized: "admin.inbox_release_count"), ready.selectedCount),
                    isPresented: $showingReleaseConfirm,
                    actions: {
                        Button(
                            String(format: String(localized: "admin.inbox_release_count"), ready.selectedCount),
                            role: .destructive
                        ) {
                            observer.releaseSelected()
                        }
                        Button(String(localized: "common.cancel"), role: .cancel) {}
                    },
                    message: {
                        Text(releaseConfirmMessage(count: ready.selectedCount))
                    }
                )
                // The released books leaving the list is the visible confirmation; the haptic and the
                // announcement carry it to people not looking at the list (HIG, Feedback).
                .haptic(.commit, trigger: releases)
                .onChange(of: ready.lastReleasedCount) { _, count in
                    guard let count else { return }
                    releases += 1
                    VoiceOverAnnouncement.post(releaseConfirmMessage(count: count))
                    observer.clearReleaseResult()
                }
        case .error(let message):
            errorBody(message: message, observer: observer)
        }
    }

    // MARK: - Ready body

    @ViewBuilder
    private func readyBody(observer: AdminInboxObserver, ready: AdminInboxReadyModel) -> some View {
        // Empty means BOTH halves are empty. An inbox holding only scan issues is populated, and
        // showing "Inbox Empty" over a list of problems would be the screen contradicting itself.
        // Mirrors AdminInboxScreen.kt.
        if ready.isEmpty {
            AdminInboxEmptyState()
        } else {
            Group {
                if isRegularWidth {
                    ScrollView {
                        padLayout(observer: observer, ready: ready)
                    }
                } else {
                    phoneList(observer: observer, ready: ready)
                }
            }
            .refreshable { observer.reload() }
            // The tray is a bar over the scroll view, whose edge effect the system draws (HIG, Toolbars).
            .safeAreaBar(edge: .bottom) {
                if ready.hasSelection && !isRegularWidth {
                    releaseBar(ready: ready)
                }
            }
        }
    }

    // MARK: - Phone layout (compact width)

    /// A system `List`: each held book is its own lazily built row (the hand-drawn group built them
    /// all at once — 2026-09-29 iOS audit, performance), with the list's separators and a tinted row
    /// background for the selected ones.
    private func phoneList(observer: AdminInboxObserver, ready: AdminInboxReadyModel) -> some View {
        List {
            ScanIssueListSection(issues: ready.scanIssues) { observer.dismissScanIssue(issueId: $0) }
            if ready.hasBooks {
                Section {
                    ForEach(ready.books) { book in
                        let isSelected = ready.selectedBookIds.contains(book.id)
                        InboxBookRow(
                            book: book,
                            isSelected: isSelected,
                            isSelecting: ready.hasSelection,
                            onTap: { observer.toggleBookSelection(bookId: book.id) },
                            onEdit: { editingBook = InboxEditTarget(id: book.id) },
                            onFindMetadata: { metadataBook = InboxMetadataTarget(book: book) }
                        )
                        .listRowBackground(InboxBookRow.background(isSelected: isSelected))
                    }
                } header: {
                    subtitleRow(ready: ready)
                        .textCase(nil)
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    // MARK: - iPad layout (regular width)

    @ViewBuilder
    private func padLayout(observer: AdminInboxObserver, ready: AdminInboxReadyModel) -> some View {
        VStack(spacing: 0) {
            padHeader(observer: observer, ready: ready)
                .padding(.horizontal, 36)
                .padding(.bottom, 16)
            ScanIssueSection(issues: ready.scanIssues) { observer.dismissScanIssue(issueId: $0) }
                .padding(.horizontal, 36)
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 320), spacing: 16)],
                spacing: 16
            ) {
                ForEach(ready.books) { book in
                    let isSelected = ready.selectedBookIds.contains(book.id)
                    // A cell in a collection draws its own surface: a grid has no rows to do it.
                    InboxBookRow(
                        book: book,
                        isSelected: isSelected,
                        isSelecting: ready.hasSelection,
                        onTap: { observer.toggleBookSelection(bookId: book.id) },
                        onEdit: { editingBook = InboxEditTarget(id: book.id) },
                        onFindMetadata: { metadataBook = InboxMetadataTarget(book: book) }
                    )
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                    .background(InboxBookRow.background(isSelected: isSelected))
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
            }
            .padding(.horizontal, 36)
            .padding(.bottom, 32)
        }
        .padding(.top, 8)
    }

    // MARK: - Subviews

    @ViewBuilder
    private func subtitleRow(ready: AdminInboxReadyModel) -> some View {
        let text: String = {
            if ready.hasSelection {
                let count = ready.selectedCount
                return count == 1
                    ? String(localized: "admin.inbox_release_count")
                    : String(format: String(localized: "admin.inbox_released_count_plural"), count)
            } else {
                return "\(ready.bookCount) \(String(localized: "admin.inbox_workflow"))"
            }
        }()
        Text(text)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func padHeader(observer: AdminInboxObserver, ready: AdminInboxReadyModel) -> some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 4) {
                Text(String(localized: "common.administration").uppercased())
                    .font(.caption.weight(.semibold))
                    .kerning(0.5)
                    .foregroundStyle(Color.luTint)
                Text(String(localized: "common.inbox"))
                    .font(.largeTitle.bold())
                if ready.hasBooks {
                    subtitleRow(ready: ready)
                        .font(.subheadline)
                }
            }
            Spacer()
            if ready.hasBooks {
                padHeaderActions(observer: observer, ready: ready)
            }
        }
    }

    /// Selection and release act on held books, so they are absent when the inbox holds only
    /// scan issues — there is nothing there to select.
    @ViewBuilder
    private func padHeaderActions(observer: AdminInboxObserver, ready: AdminInboxReadyModel) -> some View {
        HStack(spacing: 10) {
            Button {
                if ready.allSelected { observer.clearSelection() } else { observer.selectAll() }
            } label: {
                Text(ready.allSelected
                     ? String(localized: "admin.inbox_deselect_all")
                     : String(localized: "admin.inbox_select_all"))
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 18)
                    .padding(.vertical, 11)
                    .background(Color.luFill, in: Capsule())
                    .overlay(Capsule().stroke(Color.luSeparator, lineWidth: 0.5))
            }
            .buttonStyle(.plain)
            if ready.hasSelection {
                Button {
                    showingReleaseConfirm = true
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "checkmark")
                            .font(.subheadline.weight(.bold))
                        Text(String(format: String(localized: "admin.inbox_release_count"), ready.selectedCount))
                            .font(.subheadline.weight(.semibold))
                    }
                    .foregroundStyle(Color.luOnTint)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 11)
                    .background(Color.luTint, in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - Release action bar (compact / phone)

    private func releaseBar(ready: AdminInboxReadyModel) -> some View {
        Button {
            showingReleaseConfirm = true
        } label: {
            ActionLabel(
                title: String(format: String(localized: "admin.inbox_release_count"), ready.selectedCount),
                systemImage: "checkmark",
                isBusy: ready.isReleasing
            )
        }
        .prominentAction()
        .disabled(ready.isReleasing)
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .padding(.bottom, 8)
    }

    // MARK: - Error body

    @ViewBuilder
    private func errorBody(message: String, observer: AdminInboxObserver) -> some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "exclamationmark.triangle")
                .scaledFont(size: 48, weight: .light, relativeTo: .largeTitle)
                .foregroundStyle(.tertiary)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            Button(String(localized: "common.retry")) {
                observer.reload()
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Color.luTint)
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private var selectToolbarItem: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            if case .ready(let ready) = observer?.phase, ready.hasBooks {
                Button {
                    if ready.hasSelection { observer?.clearSelection() } else { observer?.selectAll() }
                } label: {
                    Text(ready.hasSelection
                         ? String(localized: "admin.inbox_deselect_all")
                         : String(localized: "admin.inbox_select_all"))
                        .font(.body)
                        .fontWeight(ready.hasSelection ? .semibold : .regular)
                        .foregroundStyle(Color.luTint)
                }
            }
        }
    }

    // MARK: - Helpers

    private func errorPresented(ready: AdminInboxReadyModel) -> Binding<Bool> {
        Binding(
            get: { ready.error != nil },
            set: { presenting in if !presenting { observer?.clearError() } }
        )
    }

    private func releaseConfirmMessage(count: Int) -> String {
        count == 1
            ? String(localized: "admin.inbox_released_count")
            : String(format: String(localized: "admin.inbox_released_count_plural"), count)
    }
}

// MARK: - Book row

/// Identifiable wrapper so the BookEdit sheet can be driven by `.sheet(item:)` off the selected id.
private struct InboxEditTarget: Identifiable {
    let id: String
}

/// Identifiable wrapper carrying the fields `MetadataMatchView` needs to seed its Audible search.
private struct InboxMetadataTarget: Identifiable {
    let id: String
    let title: String
    let author: String

    init(book: InboxBookRowModel) {
        self.id = book.id
        self.title = book.title
        self.author = book.author ?? ""
    }
}

private struct InboxBookRow: View {
    let book: InboxBookRowModel
    let isSelected: Bool
    let isSelecting: Bool
    let onTap: () -> Void
    let onEdit: () -> Void
    let onFindMetadata: () -> Void

    var body: some View {
        // Two independent hit targets: the main content toggles select-for-release, the trailing
        // ellipsis menu hosts the per-book actions (edit, find metadata). A single row-spanning
        // Button can't host a nested control, so the two sit side by side. A long-press context menu
        // mirrors the same actions for discoverability.
        HStack(spacing: 8) {
            Button(action: onTap) {
                HStack(spacing: 13) {
                    if isSelecting {
                        selectionIndicator
                    }
                    BookCoverImage(
                        bookId: book.id,
                        coverPath: book.coverPath,
                        coverHash: book.coverHash,
                        accessibilityLabel: nil
                    )
                    .frame(width: 52, height: 52)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(book.title)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        if let author = book.author {
                            Text(author)
                                .font(.footnote)
                                .foregroundStyle(Color.secondary)
                        }
                        Text(book.formattedDuration)
                            .font(.caption)
                            .foregroundStyle(Color.luLabel3)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(book.title)
            .accessibilityAddTraits(isSelected ? .isSelected : [])

            actionsMenu
        }
        .animation(.easeInOut(duration: 0.15), value: isSelected)
        .contextMenu { rowActions }
    }

    /// The row's surface: the grouped surface, washed with the accent while selected.
    static func background(isSelected: Bool) -> some View {
        Color.luSurface2.overlay(isSelected ? Color.luTint.opacity(0.08) : Color.clear)
    }

    /// Visible per-row actions: review/edit (metadata fields + collections) or match against Audible —
    /// both before releasing. A `Menu` so the two share one discoverable, HIG-standard affordance; the
    /// long-press context menu mirrors it via the same `rowActions`.
    private var actionsMenu: some View {
        Menu {
            rowActions
        } label: {
            Image(systemName: "ellipsis.circle")
                .font(.body)
                .foregroundStyle(Color.luTint)
                .frame(width: 44, height: 44)   // HIG minimum tap target
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "common.more_actions"))
    }

    @ViewBuilder
    private var rowActions: some View {
        Button(String(localized: "admin.inbox_review_edit"), systemImage: "square.and.pencil", action: onEdit)
        // Same label + icon as BookDetail's "Match on Audible" — one canonical affordance for the
        // shared MetadataMatchView flow across both entry points.
        Button(String(localized: "metadata.match_on_audible"), systemImage: "sparkles", action: onFindMetadata)
    }

    private var selectionIndicator: some View {
        ZStack {
            if isSelected {
                Circle()
                    .fill(Color.luTint)
                    .frame(width: 26, height: 26)
                Image(systemName: "checkmark")
                    .font(.system(size: 12, weight: .bold)) // decorative fixed size
                    .foregroundStyle(Color.luOnTint)
            } else {
                Circle()
                    .stroke(Color.luSeparator, lineWidth: 2)
                    .frame(width: 26, height: 26)
            }
        }
        .animation(.easeInOut(duration: 0.15), value: isSelected)
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        AdminInboxView()
            .environment(CurrentUserObserver())
    }
}
