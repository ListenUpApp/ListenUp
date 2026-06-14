import SwiftUI

/// The Discover activity section: a title and a list of recent community activities.
/// Renders loading / ready (with empty copy) / error from the observer's phase.
struct ActivitySectionView: View {
    let observer: ActivityFeedObserver

    /// Cap the section to a handful of recent items, matching the design's compact feed.
    private let maxItems = 8

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "discover.activity"))
                .font(.title2.bold())

            content
        }
    }

    @ViewBuilder
    private var content: some View {
        switch observer.phase {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
        case .ready(let items):
            if items.isEmpty {
                sectionMessage(String(localized: "discover.no_activity_yet_start_listening"))
            } else {
                let shown = Array(items.prefix(maxItems))
                VStack(spacing: 0) {
                    ForEach(Array(shown.enumerated()), id: \.element.id) { index, item in
                        ActivityRow(item: item)
                        if index < shown.count - 1 {
                            Divider().padding(.leading, 51)
                        }
                    }
                }
            }
        case .error:
            sectionMessage(String(localized: "discover.no_activity_yet_start_listening"))
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
