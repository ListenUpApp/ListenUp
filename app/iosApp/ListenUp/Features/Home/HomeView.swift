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
/// Layout adapts to width: at compact (iPhone) the screen is a single scrolling column; at regular
/// (iPad) the content is constrained to a comfortable reading width and the continue-listening rail
/// cards grow so the extra space reads as a real layout, not a stretched phone.
struct HomeView: View {
    @Environment(CurrentUserObserver.self) private var userObserver
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.dependencies) private var deps

    @State private var home: HomeViewModelWrapper?
    @State private var stats: HomeStatsObserver?
    /// One observer shared across Home's book carousels → screen-wide selection (de-dup by id is
    /// automatic via the shared `Set` in the VM).
    @State private var selection: BookSelectionObserver?

    private var user: User? { userObserver.user }
    private var isRegularWidth: Bool { horizontalSizeClass == .regular }

    /// At regular width, cap the content so it reads as a column rather than spanning the iPad.
    private var contentMaxWidth: CGFloat? { isRegularWidth ? 700 : nil }

    var body: some View {
        Group {
            if let home, let stats {
                content(home: home, stats: stats)
            } else {
                loadingContent
            }
        }
        .background(Color(.systemBackground))
        .navigationBarTitleDisplayMode(.inline)
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

    // MARK: - Content

    private func content(home: HomeViewModelWrapper, stats: HomeStatsObserver) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                phaseContent(home: home, stats: stats)
            }
            .padding(.vertical, 8)
            .frame(maxWidth: contentMaxWidth)
            .frame(maxWidth: .infinity)
        }
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
        HomeHeader(greeting: ready.timeGreeting, userName: ready.userName)
            .padding(.horizontal, 20)

        // Inline, where the content that failed would be, following the `ErrorBanner` precedent.
        if let message = home.inlineError {
            ErrorBanner(message: message)
                .padding(.horizontal, 20)
        }

        continueSection(ready.continueItems)

        HomeStatsCard(statsPhase: stats.statsPhase)
            .padding(.horizontal, 20)

        if !ready.shelves.isEmpty {
            MyShelvesRow(shelves: ready.shelves)
        }
    }

    private func errorContent(_ message: String, home: HomeViewModelWrapper) -> some View {
        ContentUnavailableView {
            Label(String(localized: "home.couldnt_load"), systemImage: "wifi.exclamationmark")
        } description: {
            Text(message)
        } actions: {
            PrimaryButton(
                title: String(localized: "common.try_again"),
                icon: "arrow.clockwise",
                action: { home.refresh() }
            )
            .frame(maxWidth: 240)
        }
        .frame(maxWidth: .infinity, minHeight: 320)
    }

    // MARK: - Continue section

    /// Horizontal inset so the rail's title aligns with the screen's content margin while the
    /// cards bleed to the edge.
    private var horizontalInset: CGFloat { 20 }
    /// Card width is width-driven so the rail reads larger on iPad.
    private var continueCardWidth: CGFloat { isRegularWidth ? 168 : 140 }

    @ViewBuilder
    private func continueSection(_ items: [ContinueItem]) -> some View {
        if items.isEmpty {
            EmptyContinueListening()
                .frame(maxWidth: .infinity)
                .padding(.horizontal, horizontalInset)
        } else {
            VStack(alignment: .leading, spacing: 12) {
                Text(String(localized: "home.continue_listening"))
                    .font(.title3.weight(.semibold))
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
// running app. Preview the sub-components (`HomeHeader`, `ShelfCard`, `HomeStatsCard`) for rich
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
