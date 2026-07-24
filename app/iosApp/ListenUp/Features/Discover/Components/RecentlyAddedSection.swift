import SwiftUI

/// The "Recently Added" section: a section title and a horizontally scrolling rail of
/// recently added cover cards, each with a relative "added" time. Card width is width-driven
/// by the caller so the rail reads larger on iPad. Renders loading / ready / error.
struct RecentlyAddedSection: View {
    let phase: RecentlyAddedPhase
    let cardWidth: CGFloat
    /// Horizontal inset so the rail's first/last cards align with the screen's content margin.
    let horizontalInset: CGFloat
    /// Screen-wide multi-select; `nil` disables selection for this rail.
    var selection: BookSelectionObserver?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(String(localized: "discover.recently_added"))
                .font(.title2.bold())
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
                .padding(.vertical, 24)
        case .ready(let books):
            if books.isEmpty {
                message(String(localized: "discover.no_recently_added_books"))
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 16) {
                        ForEach(books) { book in
                            RecentlyAddedCard(book: book, width: cardWidth, selection: selection)
                        }
                    }
                    .padding(.horizontal, horizontalInset)
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
            .padding(.vertical, 12)
    }
}
