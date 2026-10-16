import SwiftUI

/// Something New: books the reader has not started, first in their series (spec X4–X7), with a
/// Shuffle that draws ten different ones. A rail at compact width; an adaptive grid of every pick at
/// regular width.
///
/// Shuffle is a glass button (HIG, Buttons): an icon-only circle on iPhone, labelled on iPad; both are
/// named for what they do. Loading and error keep today's treatments (PR 6 owns their copy).
struct SomethingNewSection: View {
    let phase: DiscoverBooksPhase
    let cardWidth: CGFloat
    let horizontalInset: CGFloat
    let isRegular: Bool
    /// Screen-wide multi-select; `nil` disables selection here.
    var selection: BookSelectionObserver?
    let onShuffle: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            HStack(alignment: .center, spacing: Spacing.s) {
                DiscoverSectionHeader(
                    title: String(localized: "discover.something_new_ios"),
                    subtitle: String(localized: "discover.something_new_subtitle")
                )
                shuffle
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
            ProgressView()
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.xl)
        case .ready(let books):
            if books.isEmpty {
                message(String(localized: "discover.no_new_books_yet"))
            } else {
                DiscoverCardGrid(
                    items: books,
                    isRegular: isRegular,
                    cardWidth: cardWidth,
                    minimumCardWidth: 150,
                    horizontalInset: horizontalInset,
                    spacing: 18
                ) { book, width in
                    PickCard(book: book, width: width, selection: selection)
                }
            }
        case .error:
            message(String(localized: "discover.no_new_books_yet"))
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
