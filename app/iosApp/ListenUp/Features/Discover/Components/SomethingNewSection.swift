import SwiftUI

/// Something New: books the reader has not started, first in their series (spec X4–X7), with a
/// Shuffle that draws ten different ones. A rail at compact width; a grid of every pick at
/// regular width.
///
/// Shuffle is a glass button (HIG, Buttons): an icon-only circle on iPhone, labelled on iPad; both are
/// named for what they do. Every state keeps the heading (spec §3.8): a skeleton while drawing, a line
/// and Try Again (a fresh draw) on failure, and "You've started every book here." with Browse the Library.
struct SomethingNewSection: View {
    /// Cards per row in the regular-width grid: five picks, as the board lays them (spec §3.7).
    static let regularColumnCount = 5

    let phase: DiscoverBooksPhase
    let cardWidth: CGFloat
    let horizontalInset: CGFloat
    let isRegular: Bool
    /// Screen-wide multi-select; `nil` disables selection here.
    var selection: BookSelectionObserver?
    let onShuffle: () -> Void
    /// Opens the Library from "You've started every book here."; nil hides the button.
    var onBrowseLibrary: (() -> Void)?

    /// Pure: Shuffle steps aside when there is nothing to draw, or when Try Again is the action.
    static func showsShuffle(_ phase: DiscoverBooksPhase) -> Bool {
        switch phase {
        case .loading, .ready: true
        case .allStarted, .error: false
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            HStack(alignment: .center, spacing: Spacing.s) {
                DiscoverSectionHeader(
                    title: String(localized: "discover.something_new_ios"),
                    subtitle: String(localized: "discover.something_new_subtitle")
                )
                if Self.showsShuffle(phase) {
                    shuffle
                }
            }
            .padding(.horizontal, horizontalInset)

            content
        }
    }

    @ViewBuilder
    private var shuffle: some View {
        if isRegular {
            Button(action: onShuffle) {
                Label(String(localized: "discover.shuffle"), systemImage: "shuffle")
            }
            .buttonStyle(.glass)
            .accessibilityLabel(String(localized: "discover.shuffle_a11y"))
        } else {
            Button(action: onShuffle) {
                Image(systemName: "shuffle")
            }
            .buttonStyle(.glass)
            .buttonBorderShape(.circle)
            .controlSize(.large)
            .accessibilityLabel(String(localized: "discover.shuffle_a11y"))
        }
    }

    @ViewBuilder
    private var content: some View {
        switch phase {
        case .loading:
            RailSkeleton(cardWidth: cardWidth, captionLines: 3, horizontalInset: horizontalInset)
        case .allStarted:
            VStack(alignment: .leading, spacing: Spacing.xs) {
                Text(String(localized: "discover.started_everything"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if let onBrowseLibrary {
                    Button(String(localized: "discover.browse_the_library_ios"), action: onBrowseLibrary)
                        .buttonStyle(.bordered)
                        .frame(minHeight: 44)
                }
            }
            .padding(.horizontal, horizontalInset)
        case .ready(let books):
            if books.isEmpty {
                message(String(localized: "discover.no_new_books_yet"))
            } else {
                DiscoverCardGrid(
                    items: books,
                    isRegular: isRegular,
                    cardWidth: cardWidth,
                    regularColumnCount: Self.regularColumnCount,
                    horizontalInset: horizontalInset,
                    spacing: 18
                ) { book, width in
                    PickCard(book: book, width: width, selection: selection)
                }
            }
        case .error:
            InlineRetryLine(line: String(localized: "discover.error_picks"), action: onShuffle)
                .padding(.horizontal, horizontalInset)
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
