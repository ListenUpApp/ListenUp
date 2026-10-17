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
/// Layout follows the measured width (`HomeLayout`), which also decides the sections' order: the loaded book is never
/// drawn (the mini player has it); with nothing loaded, Resume leads. A wide window sets the week's stats first, beside
/// the shelves (or beside Resume).
struct HomeView: View {
    /// Selects a shell tab: Browse the Library, the Also in Progress header and the Leaderboard link leave Home here.
    var onSelectTab: (ShellTab) -> Void = { _ in }

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
        return HomeTitle.subtitle(timeOfDay: ready.timeOfDay, firstName: ready.firstName)
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
        case .error(let message):
            errorContent(message, home: home)
        }
    }

    private var loadingContent: some View {
        LoadingStateView()
            .frame(minHeight: 320)
    }

    @ViewBuilder
    private func readyContent(_ ready: HomeReady, home: HomeViewModelWrapper, stats: HomeStatsObserver) -> some View {
        let layout = layout
        // Inline, where the content that failed would be, following the `ErrorBanner` precedent.
        if let message = home.inlineError {
            ErrorBanner(message: message)
                .padding(.horizontal, layout.margin)
        }
        ForEach(
            layout.blocks(slot: ready.slot.shape, hasAlsoInProgress: !ready.alsoInProgress.isEmpty),
            id: \.self
        ) { block in
            switch block {
            case .resume:
                resumeHero(ready)
                    .padding(.horizontal, layout.margin)
            case .resumeBesideStats:
                HStack(alignment: .top, spacing: 24) {
                    resumeHero(ready).frame(maxWidth: .infinity)
                    statsCard(stats).frame(width: HomeLayout.statsWidth)
                }
                .padding(.horizontal, layout.margin)
            case .nothingToResume:
                EmptyContinueListening(onBrowseLibrary: { onSelectTab(.library) })
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, layout.margin)
            case .alsoInProgress:
                alsoInProgress(ready.alsoInProgress, layout: layout)
            case .stats:
                statsCard(stats)
                    .padding(.horizontal, layout.margin)
            case .shelves:
                MyShelvesSection(shelves: ready.shelves, layout: layout)
            case .statsBesideShelves:
                HStack(alignment: .top, spacing: 24) {
                    statsCard(stats).frame(width: HomeLayout.statsWidth)
                    MyShelvesSection(shelves: ready.shelves, layout: layout, inset: false)
                        .frame(maxWidth: .infinity)
                }
                .padding(.horizontal, layout.margin)
            }
        }
    }

    @ViewBuilder
    private func resumeHero(_ ready: HomeReady) -> some View {
        if case .resume(let resume) = ready.slot {
            ResumeHeroView(resume: resume, isOnlyBook: ready.alsoInProgress.isEmpty) {
                // P-R5 / C18: the play entry Book Detail uses, from the saved position.
                deps.playerObserver.play(bookId: resume.book.id)
            }
        }
    }

    private func statsCard(_ stats: HomeStatsObserver) -> some View {
        // PR 5 adds the Week focus; until then the link opens Discover at the top (spec §11).
        HomeStatsCard(statsPhase: stats.statsPhase, onOpenLeaderboard: { onSelectTab(.discover) })
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

    // MARK: - Also in Progress

    /// "Also in Progress ›" opens the Library (X6: PR 4 adds the In Progress filter); the rail scrolls on iPhone and is
    /// one row of width-driven columns on iPad.
    private func alsoInProgress(_ items: [ContinueItem], layout: HomeLayout) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Button {
                onSelectTab(.library)
            } label: {
                HStack(spacing: 4) {
                    Text(String(localized: "home.also_in_progress_ios"))
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
