import SwiftUI
import Shared

/// Home ("Listen Now") — the personalized landing screen.
///
/// Reads two native observers: `HomeViewModelWrapper` (greeting, slot, also in progress, shelves) and
/// `HomeStatsObserver` (the weekly stats card). Both bind their flows in their own initializers.
/// Because Home is a persistent tab root, the observers are lazily constructed in `.onAppear` and
/// kept alive for the screen's lifetime — tearing them down on `.onDisappear` would permanently
/// cancel their flows when the user pushes a detail and pops back (`@State` keeps the same dead
/// instances). This mirrors `LibraryView`/`SeriesDetailView`: observation is live whenever visible.
///
/// Layout follows the measured width (`HomeLayout`), which also decides the sections' order: every book in progress is
/// in Everything in Progress, the loaded one first; there is no Resume hero (Simon, 2026-10-27). A wide window sets the
/// week's stats first, beside the shelves (or beside the slot).
struct HomeView: View {
    /// Selects a shell tab: Browse the Library and the quiet slots' Discover leave Home here.
    var onSelectTab: (ShellTab) -> Void = { _ in }

    /// The Everything in Progress header: Library's Books filtered to In Progress (spec §6.4).
    @Environment(\.openLibraryInProgress) private var openLibraryInProgress
    /// The stats card's Leaderboard link: Discover's board on Week (spec §6.4).
    @Environment(\.openDiscoverLeaderboard) private var openDiscoverLeaderboard
    @Environment(CurrentUserObserver.self) private var userObserver
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.dependencies) private var deps

    @State private var home: HomeViewModelWrapper?
    @State private var stats: HomeStatsObserver?
    /// One observer shared across Home's book carousels → screen-wide selection (de-dup by id is
    /// automatic via the shared `Set` in the VM).
    @State private var selection: BookSelectionObserver?
    /// The scroll view's width; nil until the first layout pass measures it.
    @State private var measuredWidth: CGFloat?

    private var user: User? { userObserver.user }

    private var layout: HomeLayout {
        HomeLayout.forWidth(measuredWidth ?? (horizontalSizeClass == .regular ? 1024 : 390))
    }

    var body: some View {
        Group {
            if let home, let stats {
                content(home: home, stats: stats)
            } else {
                loadingContent
            }
        }
        .background(Color(.systemBackground))
        // The tab's name as the system large title, with the greeting beneath it (HIG, Toolbars:
        // a title "helps people understand where they are"). Selecting collapses it so the toolbar's
        // "N selected" count has the bar, as Library does.
        .navigationTitle(String(localized: "common.home"))
        .navigationSubtitle(greetingSubtitle)
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
        .onAppear {
            if home == nil { home = HomeViewModelWrapper() }
            if stats == nil { stats = HomeStatsObserver() }
            if selection == nil {
                selection = BookSelectionObserver(viewModel: deps.createBookMultiSelectViewModel())
            }
        }
    }

    /// "Good evening, Simon" once Home has loaded ("Welcome, Simon" for a new listener); the greeting alone when Home
    /// failed (spec §3.3); nothing while it loads.
    private var greetingSubtitle: String {
        switch home?.phase {
        case .ready(let ready):
            var isWelcome = false
            if case .newListener = ready.slot { isWelcome = true }
            return HomeTitle.subtitle(timeOfDay: ready.timeOfDay, firstName: ready.firstName, isWelcome: isWelcome)
        case .error(_, let timeOfDay):
            return HomeTitle.subtitle(timeOfDay: timeOfDay, firstName: nil)
        case .loading, nil:
            return ""
        }
    }

    // MARK: - Content

    private func content(home: HomeViewModelWrapper, stats: HomeStatsObserver) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                phaseContent(home: home, stats: stats)
            }
            .padding(.vertical, Spacing.xs)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { measuredWidth = $0 }
        .refreshable {
            home.refresh()
            stats.refresh()
        }
        .onChange(of: home.inlineError) { _, message in
            // An inline banner appearing is not narrated, so say it (HIG, Feedback).
            if let message { VoiceOverAnnouncement.post(message) }
        }
    }

    // MARK: - Phase

    @ViewBuilder
    private func phaseContent(home: HomeViewModelWrapper, stats: HomeStatsObserver) -> some View {
        switch home.phase {
        case .loading:
            loadingContent
        case .ready(let ready):
            readyContent(ready, home: home, stats: stats)
        case .error:
            HomeErrorCard(onRetry: { home.refresh() })
                .padding(.horizontal, layout.margin)
        }
    }

    /// Page loading (spec §3.3, H14): the slot and rail skeletons, no strip, no spinner.
    private var loadingContent: some View {
        VStack(alignment: .leading, spacing: 28) {
            HomeSkeletonView()
                .padding(.horizontal, layout.margin)
            HomeSkeletonRail(margin: layout.margin)
        }
    }

    @ViewBuilder
    private func readyContent(_ ready: HomeReady, home: HomeViewModelWrapper, stats: HomeStatsObserver) -> some View {
        let layout = layout
        ForEach(
            layout.blocks(
                slot: ready.slot.shape,
                isOffline: ready.isOffline,
                continueListeningFailed: ready.continueListeningError != nil,
                showsStrip: ready.showsArrivalStrip,
                hasEverythingInProgress: !ready.everythingInProgress.isEmpty
            ),
            id: \.self
        ) { block in
            switch block {
            case .offline:
                OfflinePill()
                    .padding(.horizontal, layout.margin)
            case .firstSyncStrip:
                FirstSyncStrip(count: ready.arrivalCount)
                    .padding(.horizontal, layout.margin)
            case .finished, .nothingInProgress, .welcome, .firstSync:
                slotContent(ready, home: home)
                    .padding(.horizontal, layout.margin)
            case .slotBesideStats:
                HStack(alignment: .top, spacing: 24) {
                    slotContent(ready, home: home).frame(maxWidth: .infinity)
                    statsCard(stats).frame(width: HomeLayout.statsWidth)
                }
                .padding(.horizontal, layout.margin)
            case .continueListeningFailed:
                ContinueListeningFailedLine(onRetry: { home.refresh() })
                    .padding(.horizontal, layout.margin)
            case .everythingInProgress:
                everythingInProgress(ready.everythingInProgress, layout: layout)
            case .stats:
                statsCard(stats)
                    .padding(.horizontal, layout.margin)
            case .shelves:
                MyShelvesSection(shelves: ready.shelves, layout: layout, isLoading: ready.showsShelvesLoading)
            case .statsBesideShelves:
                HStack(alignment: .top, spacing: 24) {
                    statsCard(stats).frame(width: HomeLayout.statsWidth)
                    MyShelvesSection(
                        shelves: ready.shelves,
                        layout: layout,
                        inset: false,
                        isLoading: ready.showsShelvesLoading
                    )
                        .frame(maxWidth: .infinity)
                }
                .padding(.horizontal, layout.margin)
            }
        }
    }

    /// Whichever slot applies, drawn: every kind takes the same position (spec §2.1). Every play goes through the play
    /// entry Book Detail uses, from the saved position (P-R5, C16, C18).
    @ViewBuilder
    private func slotContent(_ ready: HomeReady, home: HomeViewModelWrapper) -> some View {
        switch ready.slot {
        case .justFinished(let finished):
            JustFinishedCard(
                slot: finished,
                onPlay: { deps.playerObserver.play(bookId: $0) },
                onDismiss: { home.dismissFinished(bookId: finished.bookId) },
                onRate: { home.rateFinished(bookId: finished.bookId, halfStars: $0) }
            )
        case .nothingInProgress(let candidate):
            NothingInProgressView(
                candidate: candidate,
                onPlay: { deps.playerObserver.play(bookId: $0) },
                onBrowse: { onSelectTab(.library) },
                onDiscover: { onSelectTab(.discover) }
            )
        case .newListener(let covers):
            WelcomeView(covers: covers, onBrowse: { onSelectTab(.library) }, onDiscover: { onSelectTab(.discover) })
        case .firstSync:
            HomeSkeletonView()
        case .absent:
            EmptyView()
        }
    }

    private func statsCard(_ stats: HomeStatsObserver) -> some View {
        HomeStatsCard(
            statsPhase: stats.statsPhase,
            onOpenLeaderboard: openDiscoverLeaderboard,
            onRetry: { stats.refresh() }
        )
    }

    // MARK: - Everything in Progress

    /// "Everything in Progress ›" opens the Library's Books filtered to In Progress (spec §6.4); the rail scrolls on
    /// iPhone and is one row of width-driven columns on iPad.
    private func everythingInProgress(_ items: [ContinueItem], layout: HomeLayout) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Button {
                openLibraryInProgress()
            } label: {
                HStack(spacing: 4) {
                    Text(String(localized: "home.everything_in_progress_ios"))
                        .font(.title3.weight(.semibold))
                    Image(systemName: "chevron.right")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                }
                .frame(minHeight: 44)
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(.isHeader)
            .padding(.horizontal, layout.margin)

            switch layout.rail {
            case .scrolling(let cardWidth):
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 12) {
                        ForEach(items) { item in
                            ContinueCard(item: item, width: cardWidth, showsNarrator: false, selection: selection)
                        }
                    }
                    .padding(.horizontal, layout.margin)
                }
            case .row(let columns):
                HStack(alignment: .top, spacing: HomeLayout.rowGap) {
                    ForEach(items.prefix(columns)) { item in
                        ContinueCard(item: item, width: nil, showsNarrator: true, selection: selection)
                    }
                    ForEach(0..<max(0, columns - items.count), id: \.self) { _ in
                        Color.clear.frame(maxWidth: .infinity)
                    }
                }
                .padding(.horizontal, layout.margin)
            }
        }
    }
}

// MARK: - Preview

// Note: `HomeView` @State-constructs its observers from `Dependencies`, which requires the app's
// Koin graph to be initialized. The preview compiles and lays out chrome; live data needs the
// running app. Preview the sub-components (`ShelfCard`, `HomeStatsCard`) for rich
// data-driven previews.
#Preview {
    NavigationStack {
        HomeView()
    }
    .environment(CurrentUserObserver())
}

#Preview("Dark Mode") {
    NavigationStack {
        HomeView()
    }
    .environment(CurrentUserObserver())
    .preferredColorScheme(.dark)
}
