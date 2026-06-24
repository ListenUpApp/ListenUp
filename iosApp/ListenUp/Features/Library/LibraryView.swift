import SwiftUI
@preconcurrency import Shared
import UIKit

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
    @State private var selectedTab: LibraryTab = .books

    private var user: User_? { userObserver.user }

    var body: some View {
        Group {
            if let observer {
                libraryContent(observer: observer)
            } else {
                loadingState
            }
        }
        .navigationTitle(String(localized: "common.library"))
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(value: UserProfileDestination()) {
                    UserAvatarView(user: user, size: 32)
                }
                .buttonStyle(.plain)
            }
        }
        .onAppear {
            if observer == nil {
                observer = LibraryObserver(viewModel: deps.libraryViewModel)
            }
            observer?.onScreenVisible()
        }
    }

    // MARK: - Main Content

    @ViewBuilder
    private func libraryContent(observer: LibraryObserver) -> some View {
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
                onRefresh: {
                    observer.refresh()
                }
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
                }
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
        // Haptic feedback on tab change
        .sensoryFeedback(.selection, trigger: selectedTab)
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
