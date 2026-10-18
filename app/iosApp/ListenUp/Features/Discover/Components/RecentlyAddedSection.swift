import SwiftUI

/// Recently Added, last on Discover (spec §2.8): what the library gained, each book saying when. The
/// heading is the way into the Library — Books sorted Added, newest first, for this session — drawn as
/// a title with a chevron (HIG: a header that navigates). A rail at compact width; a grid of
/// every book at regular width. Renders loading / ready / error.
struct RecentlyAddedSection: View {
    /// Cards per row in the regular-width grid: six books, as the board lays them (spec §3.7).
    static let regularColumnCount = 6

    let phase: RecentlyAddedPhase
    let cardWidth: CGFloat
    /// Horizontal inset so the rail's first/last cards align with the screen's content margin.
    let horizontalInset: CGFloat
    let isRegular: Bool
    /// Screen-wide multi-select; `nil` disables selection for this rail.
    var selection: BookSelectionObserver?

    @Environment(\.openLibraryNewestFirst) private var openLibraryNewestFirst

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            header
                .padding(.horizontal, horizontalInset)

            content
        }
    }

    private var header: some View {
        Button(action: openLibraryNewestFirst) {
            HStack(spacing: Spacing.xxs) {
                Text(String(localized: "discover.recently_added_ios"))
                    .font(.title2.bold())
                    .foregroundStyle(.primary)
                Image(systemName: "chevron.forward")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(.isHeader)
        .accessibilityHint(String(localized: "discover.show_newest_in_library_a11y"))
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
                message(String(localized: "discover.no_recently_added_books"))
            } else {
                DiscoverCardGrid(
                    items: books,
                    isRegular: isRegular,
                    cardWidth: cardWidth,
                    regularColumnCount: Self.regularColumnCount,
                    horizontalInset: horizontalInset
                ) { book, width in
                    RecentlyAddedCard(book: book, width: width, selection: selection)
                }
            }
        case .error:
            message(String(localized: "discover.no_recently_added_books"))
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
