import SwiftUI

/// Listening Now, first on Discover (spec §2.8): who is listening right now and who listened in the
/// last two weeks, each as a book and a person. A rail at compact width; at regular width every
/// listener shows in a grid, so no person is hidden in a row that ran out.
///
/// Every state keeps the heading (spec §3.8): a skeleton while loading (and while an empty roster is
/// still syncing), a line and Try Again on failure, the invitation when you are alone, and one line when
/// others are here but nobody is listening. A stale roster says how old it is ("As of 2 hours ago").
struct ListeningNowSection: View {
    /// Cards per row in the regular-width grid: five listeners, as the board lays them (spec §3.7).
    static let regularColumnCount = 5

    let phase: CurrentlyListeningPhase
    let summary: ListeningSummary
    let cardWidth: CGFloat
    let horizontalInset: CGFloat
    let isRegular: Bool
    /// Screen-wide multi-select; `nil` disables selection here.
    var selection: BookSelectionObserver?
    var community: CommunityPhase = .others
    var viewer: DiscoverViewerRow?
    /// Presents the create-invite sheet; shown only to an admin.
    var onInvite: (() -> Void)?
    var onRetry: () -> Void = {}

    /// What Listening Now draws under its heading: one decision, the same table every platform reads.
    enum Treatment: Equatable {
        case skeleton
        case retry
        case invitation(canInvite: Bool)
        case nobodyListening
        case rows
    }

    /// Pure: never claim "nobody" or "just you" before the roster has synced (plan C4).
    static func treatment(
        for phase: CurrentlyListeningPhase,
        community: CommunityPhase,
        viewer: DiscoverViewerRow?
    ) -> Treatment {
        switch phase {
        case .loading: .skeleton
        case .error: .retry
        case .ready(let rows) where !rows.isEmpty: .rows
        case .ready:
            switch community {
            case .unknown: .skeleton
            case .justYou: .invitation(canInvite: viewer?.canInvite == true)
            case .others: .nobodyListening
            }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            DiscoverSectionHeader(
                title: String(localized: "discover.listening_now_ios"),
                subtitle: DiscoverCopy.listeningCount(live: summary.liveCount, recent: summary.recentCount)
            )
            .padding(.horizontal, horizontalInset)

            if case .ready(let rows) = phase, !rows.isEmpty, summary.isStale, let observedAt = summary.observedAt {
                Text(DiscoverCopy.asOf(observedAt, relativeTo: .now))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .accessibilityAddTraits(.updatesFrequently)
                    .padding(.horizontal, horizontalInset)
            }

            content
        }
    }

    @ViewBuilder
    private var content: some View {
        switch Self.treatment(for: phase, community: community, viewer: viewer) {
        case .skeleton:
            DiscoverCardGridSkeleton(
                isRegular: isRegular,
                cardWidth: cardWidth,
                regularColumnCount: Self.regularColumnCount,
                horizontalInset: horizontalInset,
                spacing: 18
            )
        case .retry:
            InlineRetryLine(line: String(localized: "discover.error_listening"), action: onRetry)
                .padding(.horizontal, horizontalInset)
        case .invitation(let canInvite):
            OnlyYouInvitation(viewer: viewer, onInvite: canInvite ? onInvite : nil)
                .padding(.horizontal, horizontalInset)
        case .nobodyListening:
            message(String(localized: "discover.nobody_listening"))
        case .rows:
            if case .ready(let rows) = phase {
                DiscoverCardGrid(
                    items: rows,
                    isRegular: isRegular,
                    cardWidth: cardWidth,
                    regularColumnCount: Self.regularColumnCount,
                    horizontalInset: horizontalInset,
                    spacing: 18
                ) { row, width in
                    ListenerCard(row: row, width: width, selection: selection)
                }
            }
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
