import SwiftUI

/// The Discover leaderboard, in a grouped card: the title, the period (Week / Month / Year / All Time)
/// always in view, the metric (Time / Books / Streak), and the top five rows with Show All for the
/// rest. Two segmented controls, not chips (HIG, Segmented controls) — menus at accessibility text
/// sizes (`LeaderboardPickerForm`). The period stays in every state (spec §3.8): loading is a skeleton of
/// rows, a failure a line and Try Again, an empty period says so; when you are alone the metric goes
/// (all three would rank you first) and the board closes with "Just you so far".
struct LeaderboardSectionView: View {
    let observer: LeaderboardObserver
    /// Set by Discover's landing so VoiceOver lands on the heading where a sighted reader lands.
    var headerFocus: AccessibilityFocusState<Bool>.Binding?
    var community: CommunityPhase = .others

    /// Pure: only you hides the metric control.
    static func showsCategory(community: CommunityPhase) -> Bool {
        community != .justYou
    }

    /// Pure: the line an empty board shows; nil while the roster is unknown (a skeleton, never a claim).
    static func emptyLine(community: CommunityPhase) -> String? {
        switch community {
        case .unknown: nil
        case .justYou: String(localized: "discover.leaderboard_just_you")
        case .others: String(localized: "discover.leaderboard_empty_period")
        }
    }

    /// Pure: the quiet line under a board of only you.
    static func footer(community: CommunityPhase) -> String? {
        community == .justYou ? String(localized: "discover.leaderboard_just_you") : nil
    }

    @State private var expanded = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

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
            if Self.showsCategory(community: community) {
                metricPicker
            }
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

    /// The period, always in view, in the form `LeaderboardPickerForm` picks for the text size.
    private var periodPicker: some View {
        styled(periodChoices)
    }

    /// Applies the form both pickers share: segmented at standard sizes, a menu at accessibility sizes.
    @ViewBuilder
    private func styled(_ picker: some View) -> some View {
        switch LeaderboardPickerForm(dynamicTypeSize) {
        case .segmented: picker.pickerStyle(.segmented)
        case .menu: picker.pickerStyle(.menu)
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
        styled(metricChoices)
    }

    private var metricChoices: some View {
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
        .labelsHidden()
        .accessibilityLabel(String(localized: "discover.leaderboard_category_group"))
    }

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        switch observer.phase {
        case .loading:
            loadingRows
        case .empty:
            if let line = Self.emptyLine(community: community) {
                sectionMessage(line)
            } else {
                loadingRows
            }
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
            if let footer = Self.footer(community: community) {
                sectionMessage(footer)
            }
        case .error:
            InlineRetryLine(line: String(localized: "discover.error_leaderboard")) { observer.refresh() }
        }
    }

    /// The loading rows, in `LeaderRow`'s own geometry so nothing moves when the board lands.
    private var loadingRows: some View {
        RowsSkeleton(
            rows: Self.collapsedCount,
            avatarSize: LeaderRow.avatarSize,
            rowHeight: LeaderRow.rowHeight,
            rankSize: LeaderRow.rankSize
        )
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

/// How the leaderboard's period and metric pickers draw (plan Q3). Equal segments at standard text
/// sizes; at accessibility sizes, where "All Time" no longer fits a quarter of the card, the HIG's
/// compact form — a menu that still shows the current value. Keyed on the text size, not on measured
/// width, so both pickers switch together and the choice is the same on every width.
enum LeaderboardPickerForm: Equatable {
    case segmented, menu

    init(_ size: DynamicTypeSize) {
        self = size.isAccessibilitySize ? .menu : .segmented
    }
}
