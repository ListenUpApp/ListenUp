import SwiftUI
import Shared

/// Home ("Listen Now") — the personalized landing screen.
///
/// Reads two native observers: `HomeViewModelWrapper` (greeting, continue-listening, shelves) and
/// `HomeStatsObserver` (the weekly stats card). Both bind their flows in their own initializers.
/// Because Home is a persistent tab root, the observers are lazily constructed in `.onAppear` and
/// kept alive for the screen's lifetime — tearing them down on `.onDisappear` would permanently
/// cancel their flows when the user pushes a detail and pops back (`@State` keeps the same dead
/// instances). This mirrors `LibraryView`/`SeriesDetailView`: observation is live whenever visible.
///
/// Layout follows the measured width (`HomeLayout`): the rails bleed the full width at every size,
/// the continue-listening cards grow with the window, and a wide window sets the week's stats beside
/// the shelves.
struct HomeView: View {
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

    /// "Good evening, Simon" once Home has loaded; nothing while it loads.
    private var greetingSubtitle: String {
        guard case .ready(let ready) = home?.phase else { return "" }
        return HomeTitle.subtitle(greeting: ready.timeGreeting, userName: ready.userName)
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
        .refreshable { home.refresh() }
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
        case .error(let message):
            errorContent(message, home: home)
        }
    }

    private var loadingContent: some View {
        LoadingStateView()
            .frame(minHeight: 320)
    }

    @ViewBuilder
    private func readyContent(
        _ ready: HomeReady,
        home: HomeViewModelWrapper,
        stats: HomeStatsObserver
    ) -> some View {
        let layout = layout
        // Inline, where the content that failed would be, following the `ErrorBanner` precedent.
        if let message = home.inlineError {
            ErrorBanner(message: message)
                .padding(.horizontal, layout.margin)
        }

        continueSection(ready.continueItems, layout: layout)

        if case .statsBesideShelves(let statsWidth) = layout.arrangement, !ready.shelves.isEmpty {
            HStack(alignment: .top, spacing: 24) {
                MyShelvesRow(shelves: ready.shelves, margin: layout.margin)
                    .frame(maxWidth: .infinity, alignment: .leading)
                HomeStatsCard(statsPhase: stats.statsPhase)
                    .frame(width: statsWidth)
            }
            .padding(.trailing, layout.margin)
        } else {
            HomeStatsCard(statsPhase: stats.statsPhase)
                .padding(.horizontal, layout.margin)

            if !ready.shelves.isEmpty {
                MyShelvesRow(shelves: ready.shelves, margin: layout.margin)
            }
        }
    }

    private func errorContent(_ message: String, home: HomeViewModelWrapper) -> some View {
        ContentUnavailableView {
            Label(String(localized: "home.couldnt_load"), systemImage: "wifi.exclamationmark")
        } description: {
            Text(message)
        } actions: {
            Button(action: { home.refresh() }) {
                ActionLabel(title: String(localized: "common.try_again"), systemImage: "arrow.clockwise")
            }
            .prominentAction()
            .frame(maxWidth: 240)
        }
        .frame(maxWidth: .infinity, minHeight: 320)
    }

    // MARK: - Continue section

    /// The rail's title aligns with the screen's margin while the cards bleed to the edge; the card
    /// size comes from the width (`HomeLayout.continueCardWidth`).
    @ViewBuilder
    private func continueSection(_ items: [ContinueItem], layout: HomeLayout) -> some View {
        let horizontalInset = layout.margin
        let continueCardWidth = layout.continueCardWidth
        if items.isEmpty {
            EmptyContinueListening()
                .frame(maxWidth: .infinity)
                .padding(.horizontal, horizontalInset)
        } else {
            VStack(alignment: .leading, spacing: 12) {
                Text(String(localized: "home.continue_listening"))
                    .font(.title3.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                    .foregroundStyle(.primary)
                    .padding(.horizontal, horizontalInset)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 16) {
                        ForEach(items) { item in
                            ContinueCard(item: item, width: continueCardWidth, selection: selection)
                        }
                    }
                    .padding(.horizontal, horizontalInset)
                }
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
