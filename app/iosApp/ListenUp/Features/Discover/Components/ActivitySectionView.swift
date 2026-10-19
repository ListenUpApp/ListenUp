import SwiftUI

/// The Discover activity section: "Activity" and the six most recent community events, then See All
/// Activity, which opens the full feed on its own screen (X33) — it never expands in place. Loading is
/// a skeleton of rows and a failure a line with its own Try Again (spec §3.8); an empty feed says so.
struct ActivitySectionView: View {
    let observer: ActivityFeedObserver
    /// The screen's selection, for a row's Add to Shelf.
    var selection: BookSelectionObserver?

    /// How many rows show before See All Activity.
    static let collapsedCount = 6

    /// Pure: the six most recent, or fewer.
    static func visibleCount(total: Int) -> Int { min(total, collapsedCount) }

    /// Pure: See All Activity is offered once there is more than the section shows.
    static func offersSeeAll(total: Int) -> Bool { total > collapsedCount }

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
                ActivityFeedMessage(text: String(localized: "discover.no_activity_yet_start_listening"))
            } else {
                ActivityRowList(items: Array(items.prefix(Self.visibleCount(total: items.count))), selection: selection)
                if Self.offersSeeAll(total: items.count) {
                    NavigationLink(value: ActivityFeedDestination()) {
                        Text(String(localized: "discover.see_all_activity_ios"))
                            .frame(minHeight: 44)
                    }
                    .buttonStyle(.borderless)
                }
            }
        case .error:
            InlineRetryLine(line: String(localized: "discover.error_activity")) { observer.refresh() }
        }
    }
}

/// Activity rows divided as a list: shared by the section and See All Activity, so the two can never
/// word or link a row differently (plan C6).
struct ActivityRowList: View {
    let items: [ActivityRowItem]
    var selection: BookSelectionObserver?

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                ActivityRow(item: item, selection: selection)
                if index < items.count - 1 {
                    Divider().padding(.leading, 56)
                }
            }
        }
    }
}

/// The feed's quiet line when there is nothing to show.
struct ActivityFeedMessage: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, Spacing.s)
    }
}
