import SwiftUI
import Shared

/// Library screen displaying the user's audiobook collection in four sections: Books, Series,
/// Authors and Narrators.
///
/// - In the compact tab bar a segmented `Picker` switches the sections (HIG, Segmented controls:
///   "consider a segmented control to switch between closely related subviews"). It replaces the
///   swipe pager and chip row, which hid the sections behind a gesture and drew their own selection.
/// - In the iPad sidebar each section is its own entry, so there is no picker and the title names
///   the section (`LibraryChrome`).
/// - The section's sort lives in the toolbar's Sort menu; its count is the navigation subtitle.
/// - Pull-to-refresh syncs all content.
struct LibraryView: View {
    @Environment(CurrentUserObserver.self) private var userObserver
    @Environment(\.dependencies) private var deps

    /// The section on screen. Owned by the tab shell (`MainShellModel`), so the in-screen switcher
    /// and the iPad sidebar's per-section entries drive the same value.
    @Binding var selectedTab: LibraryTab
    /// Whether this screen switches its own sections or is one of the sidebar's section entries.
    var chrome = LibraryChrome(tab: .library)
    /// The window's one library projection, shared by every Library tab and sidebar entry; nil until
    /// the shell has built it.
    let observer: LibraryObserver?
    @State private var selection: BookSelectionObserver?

    private var user: User? { userObserver.user }

    /// True while the Books tab is in multi-select mode — drives the inline title, the tab-bar hide,
    /// and the action bar. Only the Books tab is selectable (Series/Authors/Narrators aren't).
    private var isSelecting: Bool { selection?.isSelecting == true && selectedTab == .books }

    var body: some View {
        Group {
            if let observer, let selection {
                sectionContent(observer: observer, selection: selection)
            } else {
                loadingState
            }
        }
        // The picker sits in a top bar of its own, under the large title, where the scroll edge
        // effect treats it as chrome rather than content (HIG, Toolbars).
        .safeAreaBar(edge: .top) {
            if chrome.showsSectionPicker, !isSelecting {
                sectionPicker
            }
        }
        .navigationTitle(chrome.title(section: selectedTab))
        .navigationSubtitle(sectionCount ?? "")
        // While selecting, collapse the large "Library" title so the toolbar's principal item shows
        // the live "N selected" count (Photos idiom); the tab bar hides so the bottom action bar owns
        // the bottom strip instead of colliding with the floating Library/Search tab pills.
        .navigationBarTitleDisplayMode(isSelecting ? .inline : .large)
        .toolbar { libraryToolbar }
        .toolbar(isSelecting ? .hidden : .automatic, for: .tabBar)
        .selectionSheets(selection)
        .onAppear {
            if selection == nil {
                selection = BookSelectionObserver(viewModel: deps.createBookMultiSelectViewModel())
            }
            observer?.onScreenVisible()
        }
        // The shell may build the shared observer just after this screen first appears.
        .onChange(of: observer == nil) { _, isMissing in
            if !isMissing { observer?.onScreenVisible() }
        }
        // Leaving the Books tab cancels any in-progress selection so the bottom bar and circles
        // don't linger over the Series/Authors/Narrators tabs (which aren't selectable).
        .onChange(of: selectedTab) { _, newTab in
            if newTab != .books { selection?.exit() }
        }
    }

    // MARK: - Section picker

    /// Text-only segments with noun labels (HIG, Segmented controls: "prefer using either text or
    /// images — not a mix of both"; "use nouns or noun phrases for segment labels"). Four segments
    /// sit within the HIG's "no more than about five segments on iPhone".
    private var sectionPicker: some View {
        Picker(String(localized: "common.library"), selection: $selectedTab) {
            ForEach(LibraryTab.allCases) { section in
                Text(section.title).tag(section)
            }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
        .haptic(.selectionTick, trigger: selectedTab)
    }

    /// The section's size, e.g. "24 series", shown as the navigation subtitle.
    private var sectionCount: String? {
        guard let observer, !observer.isLoading else { return nil }
        return switch selectedTab {
        case .books: String(format: String(localized: "library.title_count"), observer.books.count)
        case .series: String(format: String(localized: "library.series_count"), observer.series.count)
        case .authors: String(format: String(localized: "library.author_count"), observer.authors.count)
        case .narrators: String(format: String(localized: "library.narrator_count"), observer.narrators.count)
        }
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private var libraryToolbar: some ToolbarContent {
        // Sync-outbox indicator — visible only when the outbox is non-empty (syncing / pending /
        // failed). Replaces the bare `isSyncing` signal with the richer SyncIndicatorViewModel surface.
        if !isSelecting {
            ToolbarItem(placement: .topBarTrailing) {
                SyncStatusIndicator()
            }
            if let observer {
                ToolbarItem(placement: .topBarTrailing) {
                    sortMenu(observer: observer)
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                NotificationBell()
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            NavigationLink(value: UserProfileDestination()) {
                UserAvatarView(user: user, size: 32)
            }
            .buttonStyle(.plain)
        }
        // Multi-select on the Books tab is entered by long-pressing a cover (see BooksContent),
        // so the toolbar surfaces only a "Done" exit + the action bar once selecting — no idle
        // "Select" button cluttering the nav bar beside the profile avatar.
        if let selection, isSelecting {
            // The Books-tab selection surface reuses the same toolbar as the Home/Discover chrome
            // (BookSelectionToolbar) so the two never drift; the inline-title switch below keeps the
            // principal "N selected" count from sitting under the large "Library" title.
            BookSelectionToolbar(selection: selection)
        }
    }

    /// The Sort menu for the section on screen; absent until the section's sort state has loaded.
    @ViewBuilder
    private func sortMenu(observer: LibraryObserver) -> some View {
        switch selectedTab {
        case .books:
            if let sortState = observer.booksSortState {
                LibrarySortMenu(
                    section: .books,
                    sortState: sortState,
                    onCategorySelected: { observer.setBooksSortCategory($0) },
                    onDirectionToggle: { observer.toggleBooksSortDirection() },
                    ignoreTitleArticles: observer.ignoreTitleArticles,
                    onToggleIgnoreArticles: { observer.toggleIgnoreTitleArticles() }
                )
            }
        case .series:
            if let sortState = observer.seriesSortState {
                LibrarySortMenu(
                    section: .series,
                    sortState: sortState,
                    onCategorySelected: { observer.setSeriesSortCategory($0) },
                    onDirectionToggle: { observer.toggleSeriesSortDirection() },
                    ignoreTitleArticles: observer.ignoreTitleArticles,
                    onToggleIgnoreArticles: { observer.toggleIgnoreTitleArticles() }
                )
            }
        case .authors:
            if let sortState = observer.authorsSortState {
                LibrarySortMenu(
                    section: .authors,
                    sortState: sortState,
                    onCategorySelected: { observer.setAuthorsSortCategory($0) },
                    onDirectionToggle: { observer.toggleAuthorsSortDirection() },
                    ignoreTitleArticles: observer.ignoreTitleArticles,
                    onToggleIgnoreArticles: { observer.toggleIgnoreTitleArticles() }
                )
            }
        case .narrators:
            if let sortState = observer.narratorsSortState {
                LibrarySortMenu(
                    section: .narrators,
                    sortState: sortState,
                    onCategorySelected: { observer.setNarratorsSortCategory($0) },
                    onDirectionToggle: { observer.toggleNarratorsSortDirection() },
                    ignoreTitleArticles: observer.ignoreTitleArticles,
                    onToggleIgnoreArticles: { observer.toggleIgnoreTitleArticles() }
                )
            }
        }
    }

    // MARK: - Main Content

    @ViewBuilder
    private func sectionContent(observer: LibraryObserver, selection: BookSelectionObserver) -> some View {
        switch selectedTab {
        case .books:
            BooksContent(
                books: observer.books,
                bookProgress: observer.bookProgress,
                sortState: observer.booksSortState,
                isLoading: observer.isLoading,
                isEmpty: observer.isEmpty,
                errorMessage: observer.errorMessage,
                ignoreTitleArticles: observer.ignoreTitleArticles,
                onRefresh: { observer.refresh() },
                selection: selection
            )
        case .series:
            SeriesContent(
                seriesList: observer.series,
                seriesProgress: observer.seriesProgress,
                sortState: observer.seriesSortState,
                ignoreTitleArticles: observer.ignoreTitleArticles
            )
        case .authors:
            ContributorListContent(
                contributors: observer.authors,
                sortState: observer.authorsSortState,
                roleKind: .author
            )
        case .narrators:
            ContributorListContent(
                contributors: observer.narrators,
                sortState: observer.narratorsSortState,
                roleKind: .narrator
            )
        }
    }

    // MARK: - Loading State

    private var loadingState: some View {
        ScrollView {
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 150), spacing: 16)],
                spacing: 20
            ) {
                ForEach(0 ..< 8, id: \.self) { _ in
                    BookCoverShimmer()
                }
            }
            .padding()
        }
        .scrollContentBackground(.hidden)
    }
}

// MARK: - Preview

#Preview("Library View") {
    NavigationStack {
        LibraryView(selectedTab: .constant(.books), observer: nil)
    }
    .environment(CurrentUserObserver())
}

#Preview("Sidebar section entry") {
    NavigationStack {
        LibraryView(
            selectedTab: .constant(.series),
            chrome: LibraryChrome(tab: .librarySection(.series)),
            observer: nil
        )
    }
    .environment(CurrentUserObserver())
}
