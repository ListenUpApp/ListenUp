import SwiftUI
@preconcurrency import Shared

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
/// (iPad) the content is constrained to a comfortable reading width and the non-hero continue rows
/// flow into a two-column grid so the extra space reads as a real layout, not a stretched phone.
struct HomeView: View {
    @Environment(CurrentUserObserver.self) private var userObserver
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var home: HomeViewModelWrapper?
    @State private var stats: HomeStatsObserver?

    private var user: User_? { userObserver.user }
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
                NavigationLink(value: UserProfileDestination()) {
                    UserAvatarView(user: user, size: 32)
                }
                .buttonStyle(.plain)
            }
        }
        .onAppear {
            if home == nil { home = HomeViewModelWrapper() }
            if stats == nil { stats = HomeStatsObserver() }
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
        .overlay(alignment: .bottom) { snackbarOverlay(home: home) }
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

    @ViewBuilder
    private func continueSection(_ items: [ContinueItem]) -> some View {
        if items.isEmpty {
            EmptyContinueListening()
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 20)
        } else {
            VStack(alignment: .leading, spacing: 12) {
                Text(String(localized: "home.continue_listening"))
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(.primary)

                ContinueHeroCard(item: items[0])

                let rest = Array(items.dropFirst())
                if !rest.isEmpty {
                    continueRows(rest)
                }
            }
            .padding(.horizontal, 20)
        }
    }

    /// Single column at compact; a width-driven grid at regular so columns flow with the canvas
    /// (one in a narrow Split View, two on a full-width iPad) rather than a fixed two-up.
    @ViewBuilder
    private func continueRows(_ items: [ContinueItem]) -> some View {
        if isRegularWidth {
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 320), spacing: 16)],
                alignment: .leading,
                spacing: 4
            ) {
                ForEach(items) { ContinueRow(item: $0) }
            }
        } else {
            VStack(spacing: 4) {
                ForEach(items) { ContinueRow(item: $0) }
            }
        }
    }

    // MARK: - Snackbar

    /// A transient native banner for the VM's snackbar channel — auto-dismisses after a few seconds.
    /// Deliberately not an alert: a snackbar should be unobtrusive and self-clearing.
    @ViewBuilder
    private func snackbarOverlay(home: HomeViewModelWrapper) -> some View {
        if let message = home.snackbar {
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .glassControl(in: RoundedRectangle(cornerRadius: 14))
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .task(id: message) {
                    try? await Task.sleep(for: .seconds(3))
                    home.clearSnackbar()
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
