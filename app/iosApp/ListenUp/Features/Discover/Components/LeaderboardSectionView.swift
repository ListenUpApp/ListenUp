import SwiftUI

/// The Discover leaderboard, in a grouped card: the title, the period (Week / Month / Year / All Time)
/// always in view, the metric (Time / Books / Streak), and the top five rows with Show All for the
/// rest. Two segmented controls, not chips (HIG, Segmented controls). Renders loading / empty / data /
/// error from the observer's phase; those treatments are PR 6's to restyle.
struct LeaderboardSectionView: View {
    let observer: LeaderboardObserver
    /// Set by Discover's landing so VoiceOver lands on the heading where a sighted reader lands.
    var headerFocus: AccessibilityFocusState<Bool>.Binding?

    @State private var expanded = false

    /// How many rows show before Show All.
    static let collapsedCount = 5

    /// Pure: every row when expanded, otherwise the top five.
    static func visibleCount(total: Int, expanded: Bool) -> Int {
        expanded ? total : min(total, collapsedCount)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            header
            periodPicker
            metricPicker
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Spacing.m)
        .background(
            Color(.secondarySystemBackground),
            in: RoundedRectangle(cornerRadius: Radius.xxl, style: .continuous)
        )
    }

    // MARK: - Header

    @ViewBuilder private var header: some View {
        let title = Text(String(localized: "discover.leaderboard"))
            .font(.title2.bold())
            .accessibilityAddTraits(.isHeader)
        if let headerFocus {
            title.accessibilityFocused(headerFocus)
        } else {
            title
        }
    }

    // MARK: - Period control

    /// The period, always in view. Four equal segments while they fit; at accessibility text sizes,
    /// where "All Time" no longer fits a quarter of the width, the HIG's compact form — a menu that
    /// still shows the current value (plan Q3). A different answer to Q3 is a change to this property.
    private var periodPicker: some View {
        ViewThatFits(in: .horizontal) {
            periodChoices.pickerStyle(.segmented)
            periodChoices.pickerStyle(.menu)
        }
    }

    private var periodChoices: some View {
        Picker(
            String(localized: "discover.leaderboard_period_group"),
            selection: Binding(
                get: { observer.selectedPeriod },
                set: { observer.selectPeriod($0) }
            )
        ) {
            ForEach(LeaderboardPeriodOption.allCases) { option in
                Text(String(localized: option.titleKey)).tag(option)
            }
        }
        .labelsHidden()
        .accessibilityLabel(String(localized: "discover.leaderboard_period_group"))
    }

    // MARK: - Metric control

    private var metricPicker: some View {
        Picker(
            String(localized: "discover.leaderboard_category_group"),
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
        .accessibilityLabel(String(localized: "discover.leaderboard_category_group"))
    }

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        switch observer.phase {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.xl)
        case .empty:
            sectionMessage(String(localized: "discover.leaderboard_empty"))
        case .data(let rows):
            let shown = Array(rows.prefix(Self.visibleCount(total: rows.count, expanded: expanded)))
            VStack(spacing: 0) {
                ForEach(Array(shown.enumerated()), id: \.element.id) { index, row in
                    LeaderRow(row: row)
                    if index < shown.count - 1, !row.isCurrentUser, !shown[index + 1].isCurrentUser {
                        Divider().padding(.leading, 60)
                    }
                }
            }
            if rows.count > Self.collapsedCount {
                Button(expanded ? String(localized: "discover.leaderboard_show_less_ios") : showAll(rows.count)) {
                    withAnimation { expanded.toggle() }
                }
                .buttonStyle(.borderless)
                .frame(minHeight: 44)
            }
        case .error:
            // Retry is offered at the screen level via pull-to-refresh.
            sectionMessage(String(localized: "discover.could_not_load_leaderboard"))
        }
    }

    private func showAll(_ count: Int) -> String {
        String(format: String(localized: "discover.leaderboard_show_all_ios"), Int32(count))
    }

    private func sectionMessage(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, Spacing.s)
    }
}
