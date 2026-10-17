import SwiftUI

/// Home's "Your Last 7 Days" card (spec §2.6): the week's total, the gold streak chip when a streak is live, the chart
/// read aloud as one summary, the top genres with their share, and a footer with the best streak and the Leaderboard
/// link. The card is pure — it renders whatever `StatsPhase` it's handed. Loading, empty and error keep their quiet
/// surfaces until Home PR 4 designs them (C16).
struct HomeStatsCard: View {
    let statsPhase: StatsPhase
    /// The footer's Leaderboard link. PR 5 adds Discover's Week focus; until then it opens Discover.
    var onOpenLeaderboard: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            content
        }
        .padding(Spacing.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.luSurface2, in: RoundedRectangle(cornerRadius: Radius.xl))
        .overlay {
            RoundedRectangle(cornerRadius: Radius.xl)
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
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(String(localized: "home.your_last_7_days_ios"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .accessibilityAddTraits(.isHeader)
                    Text(data.listenTimeLabel)
                        .font(.largeTitle.bold())
                        .lineLimit(1)
                }
                Spacer(minLength: Spacing.xs)
                if let days = data.streakChipDays { StreakChip(days: days) }
            }
            DailyListeningChart(days: data.days, maxDaySeconds: data.maxDaySeconds)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(HomeStatsText.chartSummary(days: data.days))
            if data.hasGenreData {
                Divider()
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    Text(String(localized: "home.top_genres"))
                        .font(.subheadline.weight(.semibold))
                    GenreBreakdownBars(genres: data.genres)
                }
            }
            Divider()
            footer(bestStreakDays: data.bestStreakDays)
        }
    }

    private func footer(bestStreakDays: Int?) -> some View {
        HStack {
            if let best = bestStreakDays {
                Text(HomeStatsText.bestStreak(days: best))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button(action: onOpenLeaderboard) {
                HStack(spacing: 4) {
                    Text(String(localized: "home.leaderboard"))
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.semibold))
                        .accessibilityHidden(true)
                }
            }
            .foregroundStyle(Color.luTintInk)
            .frame(minHeight: 44)
        }
    }

    // MARK: - Other phases

    private var placeholder: some View {
        HStack {
            Spacer()
            ProgressView()
                .controlSize(.regular)
            Spacer()
        }
        .frame(height: 160)
    }

    private var emptyNote: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(String(localized: "home.your_last_7_days_ios"))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
                .accessibilityAddTraits(.isHeader)

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

/// The gold streak chip (spec §2.6), read as its words.
private struct StreakChip: View {
    let days: Int

    var body: some View {
        Label(HomeStatsText.streakChip(days: days), systemImage: "flame.fill")
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Color.luStreakInk)
            .padding(.horizontal, Spacing.s)
            .frame(minHeight: 32)
            .background(Color.luStreakFill, in: Capsule())
    }
}

// MARK: - Preview

extension HomeStatsData {
    /// Memberwise initializer for previews — `HomeStatsObserver` defines only `init(from:)`,
    /// which suppresses the synthesized one, so previews need an explicit way to build samples.
    init(
        listenTimeLabel: String,
        days: [DayBar],
        maxDaySeconds: Int,
        genres: [GenreBar],
        hasGenreData: Bool,
        streakChipDays: Int? = nil,
        bestStreakDays: Int? = nil
    ) {
        self.listenTimeLabel = listenTimeLabel
        self.days = days
        self.maxDaySeconds = maxDaySeconds
        self.genres = genres
        self.hasGenreData = hasGenreData
        self.streakChipDays = streakChipDays
        self.bestStreakDays = bestStreakDays
    }
}

#Preview("Stats Card") {
    let sample = HomeStatsData(
        listenTimeLabel: "8h 12m",
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
            GenreBar(name: "Fantasy", seconds: 18_000, percent: 58),
            GenreBar(name: "Science Fiction", seconds: 9_000, percent: 29),
            GenreBar(name: "History", seconds: 3_600, percent: 11)
        ],
        hasGenreData: true,
        streakChipDays: 7,
        bestStreakDays: 14
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
