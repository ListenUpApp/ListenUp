import SwiftUI
@preconcurrency import Shared

/// Home ("Listen Now") — the personalized landing screen.
///
/// Reads two native observers: `HomeViewModelWrapper` (greeting, continue-listening, shelves) and
/// `HomeStatsObserver` (the weekly stats card). Both bind their flows in their own initializers, so
/// the view just renders the flattened phases and tears the observers down on disappear.
///
/// Layout adapts to width: at compact (iPhone) the screen is a single scrolling column; at regular
/// (iPad) the content is constrained to a comfortable reading width and the non-hero continue rows
/// flow into a two-column grid so the extra space reads as a real layout, not a stretched phone.
struct HomeView: View {
    @Environment(CurrentUserObserver.self) private var userObserver
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var home = HomeViewModelWrapper()
    @State private var stats = HomeStatsObserver()

    private var user: User_? { userObserver.user }
    private var isRegularWidth: Bool { horizontalSizeClass == .regular }

    /// At regular width, cap the content so it reads as a column rather than spanning the iPad.
    private var contentMaxWidth: CGFloat? { isRegularWidth ? 700 : nil }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                phaseContent
            }
            .padding(.vertical, 8)
            .frame(maxWidth: contentMaxWidth)
            .frame(maxWidth: .infinity)
        }
        .background(Color(.systemBackground))
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { home.refresh() }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(value: UserProfileDestination()) {
                    UserAvatarView(user: user, size: 32)
                }
                .buttonStyle(.plain)
            }
        }
        .overlay(alignment: .bottom) { snackbarOverlay }
        .onDisappear {
            home.stopObserving()
            stats.stopObserving()
        }
    }

    // MARK: - Phase

    @ViewBuilder
    private var phaseContent: some View {
        switch home.phase {
        case .loading:
            loadingContent
        case .ready(let ready):
            readyContent(ready)
        case .error(let message):
            errorContent(message)
        }
    }

    private var loadingContent: some View {
        VStack(spacing: 20) {
            ProgressView()
                .controlSize(.large)
                .tint(Color.listenUpOrange)
        }
        .frame(maxWidth: .infinity, minHeight: 320)
        .accessibilityLabel(String(localized: "common.loading"))
    }

    @ViewBuilder
    private func readyContent(_ ready: HomeReady) -> some View {
        HomeHeader(greeting: ready.greeting, userName: ready.userName)
            .padding(.horizontal, 20)

        continueSection(ready.continueItems)

        HomeStatsCard(statsPhase: stats.statsPhase)
            .padding(.horizontal, 20)

        if !ready.shelves.isEmpty {
            MyShelvesRow(shelves: ready.shelves)
        }
    }

    private func errorContent(_ message: String) -> some View {
        ContentUnavailableView {
            Label(String(localized: "home.couldnt_load"), systemImage: "wifi.exclamationmark")
        } description: {
            Text(message)
        } actions: {
            Button(String(localized: "common.try_again")) { home.refresh() }
                .buttonStyle(.borderedProminent)
                .tint(Color.listenUpOrange)
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

    /// Single column at compact; a two-column grid at regular so the rows fill the wider canvas.
    @ViewBuilder
    private func continueRows(_ items: [ContinueItem]) -> some View {
        if isRegularWidth {
            LazyVGrid(
                columns: [GridItem(.flexible(), spacing: 16), GridItem(.flexible(), spacing: 16)],
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
    private var snackbarOverlay: some View {
        if let message = home.snackbar {
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
                .overlay {
                    RoundedRectangle(cornerRadius: 14)
                        .strokeBorder(Color.glassBorder, lineWidth: 0.5)
                }
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
