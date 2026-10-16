import SwiftUI

/// Listening Now, first on Discover (spec §2.8): who is listening right now and who listened in the
/// last two weeks, each as a book and a person. A rail at compact width; at regular width every
/// listener shows in an adaptive grid, so no person is hidden in a row that ran out.
///
/// Loading and error keep today's treatments (PR 6 owns their copy).
struct ListeningNowSection: View {
    let phase: CurrentlyListeningPhase
    let summary: ListeningSummary
    let cardWidth: CGFloat
    let horizontalInset: CGFloat
    let isRegular: Bool
    /// Screen-wide multi-select; `nil` disables selection here.
    var selection: BookSelectionObserver?

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            DiscoverSectionHeader(
                title: String(localized: "discover.listening_now_ios"),
                subtitle: DiscoverCopy.listeningCount(live: summary.liveCount, recent: summary.recentCount)
            )
            .padding(.horizontal, horizontalInset)

            content
        }
    }

    @ViewBuilder
    private var content: some View {
        switch phase {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.xl)
        case .ready(let rows):
            if rows.isEmpty {
                message(String(localized: "discover.no_one_listening_right_now"))
            } else {
                DiscoverCardGrid(
                    items: rows,
                    isRegular: isRegular,
                    cardWidth: cardWidth,
                    minimumCardWidth: 150,
                    horizontalInset: horizontalInset,
                    spacing: 18
                ) { row, width in
                    ListenerCard(row: row, width: width, selection: selection)
                }
            }
        case .error:
            message(String(localized: "discover.no_one_listening_right_now"))
        }
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .padding(.horizontal, horizontalInset)
            .padding(.vertical, Spacing.s)
    }
}
