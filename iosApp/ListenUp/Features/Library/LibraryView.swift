import SwiftUI
import Shared

/// Library screen displaying the user's audiobook collection with four tabs.
///
/// Features:
/// - Four swipeable tabs: Books, Series, Authors, Narrators
/// - Glass-styled chip row for tab selection
/// - Each tab has its own sort controls and alphabet scrubber
/// - Pull-to-refresh syncs all content
/// - Chip selection and swipe gestures are synced via TabView
struct LibraryView: View {
    @Environment(CurrentUserObserver.self) private var userObserver
    @Environment(\.dependencies) private var deps

    @State private var observer: LibraryObserver?
    @State private var selection: BookSelectionObserver?
    @State private var selectedTab: LibraryTab = .books

    private var user: User? { userObserver.user }

    /// True while the Books tab is in multi-select mode — drives the inline title, the tab-bar hide,
    /// and the action bar. Only the Books tab is selectable (Series/Authors/Narrators aren't).
    private var isSelecting: Bool { selection?.isSelecting == true && selectedTab == .books }

    var body: some View {
        Group {
            if let observer, let selection {
                libraryContent(observer: observer, selection: selection)
            } else {
                loadingState
            }
        }
        .offlineTopBanner()
        .navigationTitle(String(localized: "common.library"))
        // While selecting, collapse the large "Library" title so the toolbar's principal item shows
        // the live "N selected" count (Photos idiom); the tab bar hides so the bottom action bar owns
        // the bottom strip instead of colliding with the floating Library/Search tab pills.
        .navigationBarTitleDisplayMode(isSelecting ? .inline : .large)
        .toolbar { libraryToolbar }
        .toolbar(isSelecting ? .hidden : .automatic, for: .tabBar)
        .modifier(SelectionSheets(selection: selection))
        .onAppear {
            if observer == nil {
                observer = LibraryObserver(viewModel: deps.libraryViewModel)
            }
            if selection == nil {
                selection = BookSelectionObserver(viewModel: deps.createBookMultiSelectViewModel())
            }
            observer?.onScreenVisible()
        }
        // Leaving the Books tab cancels any in-progress selection so the bottom bar and circles
        // don't linger over the Series/Authors/Narrators tabs (which aren't selectable).
        .onChange(of: selectedTab) { _, newTab in
            if newTab != .books { selection?.exit() }
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

    // MARK: - Main Content

    @ViewBuilder
    private func libraryContent(observer: LibraryObserver, selection: BookSelectionObserver) -> some View {
        // Swipeable content - extends edge to edge
        TabView(selection: $selectedTab) {
            // Books Tab
            BooksContent(
                books: observer.books,
                bookProgress: observer.bookProgress,
                sortState: observer.booksSortState,
                isLoading: observer.isLoading,
                isEmpty: observer.isEmpty,
                errorMessage: observer.errorMessage,
                onCategorySelected: { category in
                    observer.setBooksSortCategory(category)
                },
                onDirectionToggle: {
                    observer.toggleBooksSortDirection()
                },
                ignoreTitleArticles: observer.ignoreTitleArticles,
                onToggleIgnoreArticles: { observer.toggleIgnoreTitleArticles() },
                onRefresh: {
                    observer.refresh()
                },
                selection: selection
            )
            .tag(LibraryTab.books)

            // Series Tab
            SeriesContent(
                seriesList: observer.series,
                seriesProgress: observer.seriesProgress,
                sortState: observer.seriesSortState,
                onCategorySelected: { category in
                    observer.setSeriesSortCategory(category)
                },
                onDirectionToggle: {
                    observer.toggleSeriesSortDirection()
                },
                ignoreTitleArticles: observer.ignoreTitleArticles,
                onToggleIgnoreArticles: { observer.toggleIgnoreTitleArticles() }
            )
            .tag(LibraryTab.series)

            // Authors Tab
            ContributorListContent(
                contributors: observer.authors,
                sortState: observer.authorsSortState,
                roleKind: .author,
                onCategorySelected: { observer.setAuthorsSortCategory($0) },
                onDirectionToggle: { observer.toggleAuthorsSortDirection() }
            )
            .tag(LibraryTab.authors)

            // Narrators Tab
            ContributorListContent(
                contributors: observer.narrators,
                sortState: observer.narratorsSortState,
                roleKind: .narrator,
                onCategorySelected: { observer.setNarratorsSortCategory($0) },
                onDirectionToggle: { observer.toggleNarratorsSortDirection() }
            )
            .tag(LibraryTab.narrators)
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .scrollContentBackground(.hidden)
        .background(.clear)
        .ignoresSafeArea(edges: .bottom)
        // Animation removed — conflicts with .page TabView programmatic selection
        // Glass chip row overlaid at top
        .safeAreaInset(edge: .top) {
            LibraryChipRow(selectedTab: $selectedTab)
        }
        // Tab-change haptic is emitted (gated) by LibraryChipRow's `.haptic` modifier.
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
            .padding(.bottom, 100)
        }
        .scrollContentBackground(.hidden)
        .ignoresSafeArea(edges: .bottom)
        .safeAreaInset(edge: .top) {
            LibraryChipRow(selectedTab: .constant(.books))
        }
    }
}

// MARK: - Selection sheets

/// Hosts the two bulk picker sheets, bound to the shared `BookSelectionObserver`. Extracted into a
/// modifier so the body's `Group`/toolbar stays readable; a no-op when `selection` is nil.
private struct SelectionSheets: ViewModifier {
    let selection: BookSelectionObserver?

    func body(content: Content) -> some View {
        if let selection {
            content
                .sheet(isPresented: Binding(
                    get: { selection.showShelfPicker },
                    set: { selection.showShelfPicker = $0 }
                )) {
                    BulkShelfPickerSheet(
                        observer: selection,
                        count: selection.selectedBookIds.count
                    ) { selection.showShelfPicker = false }
                }
                .sheet(isPresented: Binding(
                    get: { selection.isAdmin && selection.showCollectionPicker },
                    set: { selection.showCollectionPicker = $0 }
                )) {
                    BulkCollectionPickerSheet(
                        observer: selection,
                        count: selection.selectedBookIds.count
                    ) { selection.showCollectionPicker = false }
                }
        } else {
            content
        }
    }
}

// MARK: - Preview

#Preview("Library View") {
    NavigationStack {
        LibraryView()
    }
    .environment(CurrentUserObserver())
}

#Preview("Loading State") {
    NavigationStack {
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
        .safeAreaInset(edge: .top) {
            LibraryChipRow(selectedTab: .constant(.books))
        }
        .navigationTitle("Library")
    }
}
