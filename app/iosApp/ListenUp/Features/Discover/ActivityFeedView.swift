import SwiftUI

/// See All Activity (X33): the community feed in full (up to the 100 rows the feed keeps), in the
/// section's own rows with the same context menu and VoiceOver actions. Pull to refresh asks the
/// server for anything newer. Its observers are made on appear and kept for the screen's life, the
/// `DiscoverView` rule; its own selection carries a row's Add to Shelf.
struct ActivityFeedView: View {
    @Environment(\.dependencies) private var deps
    @State private var observer: ActivityFeedObserver?
    @State private var selection: BookSelectionObserver?

    /// The narrowest a column of rows gets before the grid drops one: a sentence and a cover fit.
    private static let columnMinWidth: CGFloat = 360

    var body: some View {
        ScrollView {
            if let observer {
                content(observer)
                    .padding(.horizontal, Spacing.m)
                    .padding(.vertical, Spacing.s)
            }
        }
        .refreshable { observer?.refresh() }
        .navigationTitle(String(localized: "discover.activity"))
        .navigationBarTitleDisplayMode(.inline)
        .bookSelectionChrome(selection)
        .onAppear {
            if observer == nil { observer = ActivityFeedObserver() }
            if selection == nil {
                selection = BookSelectionObserver(viewModel: deps.createBookMultiSelectViewModel())
            }
        }
    }

    @ViewBuilder
    private func content(_ observer: ActivityFeedObserver) -> some View {
        switch observer.phase {
        case .loading:
            RowsSkeleton(rows: ActivitySectionView.collapsedCount)
        case .ready(let items):
            if items.isEmpty {
                ActivityFeedMessage(text: String(localized: "discover.no_activity_yet_start_listening"))
            } else {
                // Columns emerge from the width (rule 12): one on an iPhone, two or more across an iPad.
                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: Self.columnMinWidth), spacing: Spacing.l)],
                    spacing: 0
                ) {
                    ForEach(items) { item in
                        VStack(spacing: 0) {
                            ActivityRow(item: item, selection: selection)
                            Divider().padding(.leading, 56)
                        }
                    }
                }
            }
        case .error:
            InlineRetryLine(line: String(localized: "discover.error_activity")) { observer.refresh() }
        }
    }
}
