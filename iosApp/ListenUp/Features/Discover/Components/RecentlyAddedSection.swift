import SwiftUI

/// The "Recently Added" section: a section title and a vertical list of recently added
/// books, each with a relative "added" time. Renders loading / ready / error.
struct RecentlyAddedSection: View {
    let phase: RecentlyAddedPhase

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "discover.recently_added"))
                .font(.title2.bold())

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
                VStack(spacing: 0) {
                    ForEach(Array(books.enumerated()), id: \.element.id) { index, book in
                        RecentlyAddedRow(book: book)
                        if index < books.count - 1 {
                            Divider().padding(.leading, 65)
                        }
                    }
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
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 12)
    }
}
