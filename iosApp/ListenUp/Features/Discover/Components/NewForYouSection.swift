import SwiftUI

/// The "New for You" section: a section title and a horizontally scrolling cover rail.
/// Card width is width-driven by the caller so the rail reads larger on iPad.
struct NewForYouSection: View {
    let phase: DiscoverBooksPhase
    let cardWidth: CGFloat
    /// Horizontal inset so the rail's first/last cards align with the screen's content margin.
    let horizontalInset: CGFloat
    /// Screen-wide multi-select; `nil` disables selection for this rail.
    var selection: BookSelectionObserver?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(String(localized: "discover.new_for_you"))
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
                message(String(localized: "discover.no_new_books_yet"))
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 16) {
                        ForEach(books) { book in
                            DiscoverCoverCard(book: book, width: cardWidth, selection: selection)
                        }
                    }
                    .padding(.horizontal, horizontalInset)
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
            .padding(.vertical, 12)
    }
}
