import SwiftUI

/// The Discover leaderboard section: a title, a Week / Month / All period control, a
/// Time / Books / Streak metric control, and flat ranked rows. Renders loading / empty /
/// data / error from the observer's phase.
struct LeaderboardSectionView: View {
    let observer: LeaderboardObserver

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            metricPicker
            content
        }
    }

    // MARK: - Metric control

    private var metricPicker: some View {
        Picker(
            String(localized: "discover.leaderboard"),
            selection: Binding(
                get: { observer.selectedMetric },
                set: { observer.selectMetric($0) }
            )
        ) {
            ForEach(LeaderboardMetric.allCases) { metric in
                Text(String(localized: metric.titleKey)).tag(metric)
            }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
    }

    // MARK: - Header

    private var header: some View {
        HStack(alignment: .center, spacing: 12) {
            Text(String(localized: "discover.leaderboard"))
                .font(.title2.bold())

            Spacer(minLength: 8)

            Picker(
                String(localized: "discover.leaderboard"),
                selection: Binding(
                    get: { observer.selectedPeriod },
                    set: { observer.selectPeriod($0) }
                )
            ) {
                ForEach(LeaderboardSelection.allCases) { selection in
                    Text(String(localized: selection.titleKey)).tag(selection)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(maxWidth: 200)
        }
    }

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        switch observer.phase {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
        case .empty:
            sectionMessage(String(localized: "discover.leaderboard_empty"))
        case .data(let rows):
            VStack(spacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                    LeaderRow(row: row)
                    if index < rows.count - 1, !row.isCurrentUser {
                        Divider().padding(.leading, 60)
                    }
                }
            }
        case .error:
            // Retry is offered at the screen level via pull-to-refresh.
            sectionMessage(String(localized: "discover.could_not_load_leaderboard"))
        }
    }

    private func sectionMessage(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 12)
    }
}
