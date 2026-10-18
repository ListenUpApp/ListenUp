import SwiftUI

/// The Discover activity section: "Activity" and the six most recent community events, with
/// See All Activity for the rest. Loading is a skeleton of rows and a failure a line with its own Try
/// Again (spec §3.8); an empty feed says so.
///
/// Until PR 6's See All Activity screen exists, the link expands the list in place to the full feed
/// and then reads Show Less (plan Q1) — the leaderboard's Show All mechanism, so rows 7–100 are never
/// unreachable. PR 6 swaps `toggleExpanded` for navigation to its screen.
struct ActivitySectionView: View {
    let observer: ActivityFeedObserver

    @State private var expanded = false

    /// How many rows show before See All Activity.
    static let collapsedCount = 6

    /// Pure: every row when expanded, otherwise the six most recent.
    static func visibleCount(total: Int, expanded: Bool) -> Int {
        expanded ? total : min(total, collapsedCount)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(String(localized: "discover.activity"))
                .font(.title2.bold())
                .accessibilityAddTraits(.isHeader)

            content
        }
    }

    @ViewBuilder
    private var content: some View {
        switch observer.phase {
        case .loading:
            RowsSkeleton(rows: Self.collapsedCount)
        case .ready(let items):
            if items.isEmpty {
                sectionMessage(String(localized: "discover.no_activity_yet_start_listening"))
            } else {
                let shown = Array(items.prefix(Self.visibleCount(total: items.count, expanded: expanded)))
                VStack(spacing: 0) {
                    ForEach(Array(shown.enumerated()), id: \.element.id) { index, item in
                        ActivityRow(item: item)
                        if index < shown.count - 1 {
                            Divider().padding(.leading, 56)
                        }
                    }
                }
                if items.count > Self.collapsedCount {
                    Button(
                        expanded
                            ? String(localized: "discover.activity_show_less_ios")
                            : String(localized: "discover.see_all_activity_ios"),
                        action: toggleExpanded
                    )
                    .buttonStyle(.borderless)
                    .frame(minHeight: 44)
                }
            }
        case .error:
            InlineRetryLine(line: String(localized: "discover.error_activity")) { observer.refresh() }
        }
    }

    /// Q1's default: expand in place. PR 6 replaces this with navigation to See All Activity.
    private func toggleExpanded() {
        withAnimation { expanded.toggle() }
    }

    private func sectionMessage(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, Spacing.s)
    }
}
