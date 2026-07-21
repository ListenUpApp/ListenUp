import SwiftUI
import Shared

/// Discover — the social landing screen for finding new audiobooks and seeing the community.
///
/// Five sections, wired to three shared ViewModels via native observers:
/// - **New for You** (`DiscoverObserver.newForYou`) — a horizontal cover rail.
/// - **Recently Added** (`DiscoverObserver.recentlyAdded`) — a horizontal cover rail.
/// - **What Others Are Listening To** (`DiscoverObserver.currentlyListening`) — a horizontal
///   rail of people-and-their-current-book cards, one per user.
/// - **Leaderboard** (`LeaderboardObserver`) — ranked rows with a Week / Month / All control.
/// - **Activity** (`ActivityFeedObserver`) — the community feed.
///
/// Because Discover is a persistent tab root, the observers are lazily constructed in
/// `.onAppear` and kept for the screen's lifetime — tearing them down on `.onDisappear`
/// would permanently cancel their flows when the user pushes a book detail and pops back.
/// This mirrors `HomeView`/`LibraryView`.
///
/// Layout is width-responsive: a single scrolling column at compact width (iPhone); at
/// regular width (iPad) the rail cards grow and Leaderboard + Activity sit side-by-side.
struct DiscoverView: View {
    @Environment(CurrentUserObserver.self) private var userObserver
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.dependencies) private var deps

    @State private var discover: DiscoverObserver?
    @State private var leaderboard: LeaderboardObserver?
    @State private var activity: ActivityFeedObserver?
    /// One observer shared across all of Discover's book rails → screen-wide selection (de-dup by
    /// id is automatic via the shared `Set` in the VM).
    @State private var selection: BookSelectionObserver?

    private var user: User? { userObserver.user }
    private var isRegularWidth: Bool { horizontalSizeClass == .regular }
    private var horizontalInset: CGFloat { isRegularWidth ? 32 : 20 }
    private var railCardWidth: CGFloat { isRegularWidth ? 170 : 144 }

    var body: some View {
        Group {
            if let discover, let leaderboard, let activity {
                content(discover: discover, leaderboard: leaderboard, activity: activity)
            } else {
                LoadingStateView().frame(minHeight: 320)
            }
        }
        .background(Color(.systemBackground))
        .navigationTitle(String(localized: "common.discover"))
        // Collapse the large title while selecting so the selection toolbar's principal "N selected"
        // count doesn't render alongside a large "Discover" title (Home is already .inline).
        .navigationBarTitleDisplayMode(selection?.isSelecting == true ? .inline : .large)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(value: UserProfileDestination()) {
                    UserAvatarView(user: user, size: 32)
                }
                .buttonStyle(.plain)
            }
        }
        .bookSelectionChrome(selection)
        .onAppear {
            if discover == nil { discover = DiscoverObserver() }
            if leaderboard == nil { leaderboard = LeaderboardObserver() }
            if activity == nil { activity = ActivityFeedObserver() }
            if selection == nil {
                selection = BookSelectionObserver(viewModel: deps.createBookMultiSelectViewModel())
            }
            leaderboard?.setCurrentUserId(user?.idString)
        }
        .onChange(of: user?.idString) { _, newId in
            leaderboard?.setCurrentUserId(newId)
        }
    }

    // MARK: - Content

    private func content(
        discover: DiscoverObserver,
        leaderboard: LeaderboardObserver,
        activity: ActivityFeedObserver
    ) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                // The rails manage their own horizontal insets so cards can bleed to the edge.
                NewForYouSection(
                    phase: discover.newForYou,
                    cardWidth: railCardWidth,
                    horizontalInset: horizontalInset,
                    selection: selection
                )

                RecentlyAddedSection(
                    phase: discover.recentlyAdded,
                    cardWidth: railCardWidth,
                    horizontalInset: horizontalInset,
                    selection: selection
                )

                CurrentlyListeningSection(
                    phase: discover.currentlyListening,
                    cardWidth: railCardWidth,
                    horizontalInset: horizontalInset,
                    selection: selection
                )

                socialSections(leaderboard: leaderboard, activity: activity)
                    .padding(.horizontal, horizontalInset)
            }
            .padding(.vertical, 8)
        }
        .refreshable {
            discover.refresh()
            activity.refresh()
        }
    }

    /// Leaderboard + Activity: stacked at compact width, side-by-side at regular width.
    @ViewBuilder
    private func socialSections(
        leaderboard: LeaderboardObserver,
        activity: ActivityFeedObserver
    ) -> some View {
        if isRegularWidth {
            HStack(alignment: .top, spacing: 28) {
                LeaderboardSectionView(observer: leaderboard)
                    .frame(maxWidth: .infinity, alignment: .topLeading)
                ActivitySectionView(observer: activity)
                    .frame(maxWidth: .infinity, alignment: .topLeading)
            }
        } else {
            VStack(alignment: .leading, spacing: 28) {
                LeaderboardSectionView(observer: leaderboard)
                ActivitySectionView(observer: activity)
            }
        }
    }
}

// MARK: - Preview

// Note: `DiscoverView` @State-constructs its observers from `Dependencies`, which requires the
// app's Koin graph. The preview compiles and lays out chrome; live data needs the running app.
// Preview sub-components (`LeaderRow`, `ActivityRow`, `DiscoverCoverCard`) for data-driven previews.
#Preview {
    NavigationStack {
        DiscoverView()
    }
    .environment(CurrentUserObserver())
}
