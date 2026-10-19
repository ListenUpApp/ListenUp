import SwiftUI
import Shared

/// Discover — the social landing screen: the people on this server, and something new for you.
///
/// Reads, top to bottom (spec §2.8): **Listening Now** → **Something New** → **Leaderboard** →
/// **Activity** → **Shelves by Others** → **Recently Added**, from three shared ViewModels via native
/// observers. `DiscoverSection` holds that order and how a regular-width window pairs the
/// Leaderboard with Activity side by side (§3.7).
///
/// Because Discover is a persistent tab root, the observers are lazily constructed in
/// `.onAppear` and kept for the screen's lifetime — tearing them down on `.onDisappear`
/// would permanently cancel their flows when the user pushes a book detail and pops back.
/// This mirrors `HomeView`/`LibraryView`.
///
/// `leaderboardLandingRequest` is the shell's landing (`MainShellModel.landOnLeaderboard`): each bump
/// selects Week, scrolls the leaderboard into view, and moves VoiceOver to its heading (§6.4).
struct DiscoverView: View {
    /// The shell's leaderboard-landing counter; Discover answers each new value once.
    var leaderboardLandingRequest = 0
    /// Switches the shell's tab: Browse the Library, from "You've started every book here."
    var onSelectTab: (ShellTab) -> Void = { _ in }

    @Environment(CurrentUserObserver.self) private var userObserver
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.dependencies) private var deps

    @State private var discover: DiscoverObserver?
    @State private var leaderboard: LeaderboardObserver?
    @State private var activity: ActivityFeedObserver?
    /// One observer shared across all of Discover's book rails → screen-wide selection (de-dup by
    /// id is automatic via the shared `Set` in the VM).
    @State private var selection: BookSelectionObserver?
    /// The last landing request answered, so a re-render never lands twice.
    @State private var handledLanding = 0
    @AccessibilityFocusState private var leaderboardFocused: Bool
    /// The create-invite sheet, presented from the only-you invitation (an admin's), as Admin presents it.
    @State private var isInviting = false

    private var user: User? { userObserver.user }
    private var isRegularWidth: Bool { horizontalSizeClass == .regular }
    private var horizontalInset: CGFloat { isRegularWidth ? 32 : 20 }
    private var railCardWidth: CGFloat { 150 }

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
                NotificationBell()
            }
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(value: UserProfileDestination()) {
                    UserAvatarView(user: user, size: 32)
                }
                .buttonStyle(.plain)
            }
        }
        .bookSelectionChrome(selection)
        .sheet(isPresented: $isInviting) {
            CreateInviteView()
        }
        .onAppear {
            if discover == nil { discover = DiscoverObserver() }
            if leaderboard == nil { leaderboard = LeaderboardObserver() }
            if activity == nil { activity = ActivityFeedObserver() }
            if selection == nil {
                selection = BookSelectionObserver(viewModel: deps.createBookMultiSelectViewModel())
            }
        }
    }

    // MARK: - Content

    private func content(
        discover: DiscoverObserver,
        leaderboard: LeaderboardObserver,
        activity: ActivityFeedObserver
    ) -> some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    if isRegularWidth {
                        ForEach(DiscoverSection.regularRows, id: \.self) { row in
                            regularRow(row, discover: discover, leaderboard: leaderboard, activity: activity)
                        }
                    } else {
                        ForEach(DiscoverSection.compactOrder, id: \.self) { section in
                            self.section(section, discover: discover, leaderboard: leaderboard, activity: activity)
                                .id(section)
                        }
                    }
                }
                .padding(.vertical, Spacing.xs)
            }
            .refreshable {
                discover.refresh()
                leaderboard.refresh()
                activity.refresh()
            }
            .onAppear { landIfRequested(proxy, leaderboard: leaderboard) }
            .onChange(of: leaderboardLandingRequest) { _, _ in landIfRequested(proxy, leaderboard: leaderboard) }
        }
    }

    /// A regular-width row: one section across the width, or the Leaderboard and Activity side by side,
    /// split about 1 : 1.15 as the board pairs them (`DiscoverPairedColumns`).
    @ViewBuilder
    private func regularRow(
        _ row: [DiscoverSection],
        discover: DiscoverObserver,
        leaderboard: LeaderboardObserver,
        activity: ActivityFeedObserver
    ) -> some View {
        if row.count == 2 {
            DiscoverPairedColumns(spacing: 22) {
                ForEach(row, id: \.self) { section in
                    self.section(section, discover: discover, leaderboard: leaderboard, activity: activity)
                        .frame(maxWidth: .infinity, alignment: .topLeading)
                        .id(section)
                }
            }
            .padding(.horizontal, horizontalInset)
        } else if let only = row.first {
            section(only, discover: discover, leaderboard: leaderboard, activity: activity)
                .id(only)
        }
    }

    /// One section. The rails manage their own horizontal insets so cards can bleed to the edge; the
    /// Leaderboard and Activity are inset here at compact width and by their row at regular width.
    @ViewBuilder
    private func section(
        _ section: DiscoverSection,
        discover: DiscoverObserver,
        leaderboard: LeaderboardObserver,
        activity: ActivityFeedObserver
    ) -> some View {
        let communityInset: CGFloat = isRegularWidth ? 0 : horizontalInset
        switch section {
        case .listeningNow:
            ListeningNowSection(
                phase: discover.currentlyListening,
                summary: discover.listeningSummary,
                cardWidth: railCardWidth,
                horizontalInset: horizontalInset,
                isRegular: isRegularWidth,
                selection: selection,
                community: discover.communitySize,
                viewer: discover.viewer,
                onInvite: discover.viewer?.canInvite == true ? { isInviting = true } : nil,
                onRetry: { discover.retryListeningNow() }
            )
        case .somethingNew:
            SomethingNewSection(
                phase: discover.newForYou,
                cardWidth: railCardWidth,
                horizontalInset: horizontalInset,
                isRegular: isRegularWidth,
                selection: selection,
                onShuffle: { discover.shuffle() },
                onBrowseLibrary: { onSelectTab(.library) }
            )
        case .leaderboard:
            LeaderboardSectionView(
                observer: leaderboard,
                headerFocus: $leaderboardFocused,
                community: discover.communitySize
            )
                .padding(.horizontal, communityInset)
        case .activity:
            ActivitySectionView(observer: activity, selection: selection)
                .padding(.horizontal, communityInset)
        case .shelvesByOthers:
            ShelvesByOthersSection(
                phase: discover.shelves,
                horizontalInset: horizontalInset,
                isRegular: isRegularWidth,
                onRetry: { discover.retryShelvesByOthers() }
            )
        case .recentlyAdded:
            RecentlyAddedSection(
                phase: discover.recentlyAdded,
                cardWidth: 132,
                horizontalInset: horizontalInset,
                isRegular: isRegularWidth,
                selection: selection,
                onRetry: { discover.retryRecentlyAdded() }
            )
        }
    }

    // MARK: - Landing

    /// Answers the shell's leaderboard landing once: Week first, so the board arrives on the right
    /// period, then the board scrolled to the top, then VoiceOver focus on its heading (spec §6.4).
    private func landIfRequested(_ proxy: ScrollViewProxy, leaderboard: LeaderboardObserver) {
        guard leaderboardLandingRequest > handledLanding else { return }
        handledLanding = leaderboardLandingRequest
        leaderboard.selectPeriod(.week)
        withAnimation { proxy.scrollTo(DiscoverSection.leaderboard, anchor: .top) }
        leaderboardFocused = true
    }
}

// MARK: - Preview

// Note: `DiscoverView` @State-constructs its observers from `Dependencies`, which requires the
// app's Koin graph. The preview compiles and lays out chrome; live data needs the running app.
// Preview sub-components (`LeaderRow`, `ActivityRow`, `PickCard`) for data-driven previews.
#Preview {
    NavigationStack {
        DiscoverView()
    }
    .environment(CurrentUserObserver())
}
