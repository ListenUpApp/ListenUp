import SwiftUI

/// The Home "This week" stats card — a Screen-Time-style breakdown of the user's listening.
///
/// A glass-backed surface carrying a "This week" header with the
/// total listen time, an optional streak badge, the 7-day chart, and the top-genre bars. The card
/// is pure — it renders whatever `StatsPhase` it's handed, so Task 5 simply passes
/// `observer.statsPhase`. Each phase has its own quiet surface: a loading placeholder, a gentle
/// empty note, the content, or an unobtrusive inline error (no alert, no global error bus).
struct HomeStatsCard: View {
    let statsPhase: StatsPhase

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.luSurface2, in: RoundedRectangle(cornerRadius: 20))
        .overlay {
            RoundedRectangle(cornerRadius: 20)
                .strokeBorder(Color.luSeparator, lineWidth: 0.5)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch statsPhase {
        case .loading:
            placeholder
        case .empty:
            emptyNote
        case .data(let data):
            dataContent(data)
        case .error:
            errorNote
        }
    }

    // MARK: - Data

    private func dataContent(_ data: HomeStatsData) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            header(listenTimeLabel: data.listenTimeLabel)

            if data.hasStreak {
                StreakBadge(currentStreak: data.currentStreak, longestStreak: data.longestStreak)
            }

            DailyListeningChart(days: data.days, maxDaySeconds: data.maxDaySeconds)

            if data.hasGenreData {
                VStack(alignment: .leading, spacing: 10) {
                    Text(String(localized: "home.top_genres").uppercased())
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)

                    GenreBreakdownBars(genres: data.genres)
                }
            }
        }
    }

    private func header(listenTimeLabel: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(String(localized: "home.this_week").uppercased())
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)

            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(listenTimeLabel)
                    .font(.largeTitle.weight(.bold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                Text(String(localized: "home.listened"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }

    // MARK: - Other phases

    private var placeholder: some View {
        HStack {
            Spacer()
            ProgressView()
                .controlSize(.regular)
                .tint(Color.listenUpOrange)
            Spacer()
        }
        .frame(height: 160)
    }

    private var emptyNote: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(String(localized: "home.this_week").uppercased())
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)

            Text(String(localized: "home.start_listening_to_see_your"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var errorNote: some View {
        Label(String(localized: "home.couldnt_load_stats"), systemImage: "exclamationmark.triangle")
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Preview

extension HomeStatsData {
    /// Memberwise initializer for previews — `HomeStatsObserver` defines only `init(from:)`,
    /// which suppresses the synthesized one, so previews need an explicit way to build samples.
    init(
        listenTimeLabel: String,
        currentStreak: Int,
        longestStreak: Int,
        hasStreak: Bool,
        days: [DayBar],
        maxDaySeconds: Int,
        genres: [GenreBar],
        hasGenreData: Bool
    ) {
        self.listenTimeLabel = listenTimeLabel
        self.currentStreak = currentStreak
        self.longestStreak = longestStreak
        self.hasStreak = hasStreak
        self.days = days
        self.maxDaySeconds = maxDaySeconds
        self.genres = genres
        self.hasGenreData = hasGenreData
    }
}

#Preview("Stats Card") {
    let sample = HomeStatsData(
        listenTimeLabel: "8h 12m",
        currentStreak: 7,
        longestStreak: 14,
        hasStreak: true,
        days: [
            DayBar(dayOffset: 6, seconds: 1_800),
            DayBar(dayOffset: 5, seconds: 3_600),
            DayBar(dayOffset: 4, seconds: 0),
            DayBar(dayOffset: 3, seconds: 5_400),
            DayBar(dayOffset: 2, seconds: 2_700),
            DayBar(dayOffset: 1, seconds: 900),
            DayBar(dayOffset: 0, seconds: 4_200)
        ],
        maxDaySeconds: 5_400,
        genres: [
            GenreBar(name: "Fantasy", seconds: 18_000),
            GenreBar(name: "Science Fiction", seconds: 9_000),
            GenreBar(name: "History", seconds: 3_600)
        ],
        hasGenreData: true
    )

    return ScrollView {
        VStack(spacing: 16) {
            HomeStatsCard(statsPhase: .data(sample))
            HomeStatsCard(statsPhase: .loading)
            HomeStatsCard(statsPhase: .empty)
            HomeStatsCard(statsPhase: .error)
        }
        .padding()
    }
}
