import SwiftUI

/// Everyone reading a book, uncapped: where Book Detail's "See All N Readers" leads.
struct BookReadersListView: View {
    let bookId: String

    var body: some View {
        WithObserver(id: bookId) {
            BookReadersObserver(viewModel: $0.createBookReadersViewModel(bookId: bookId))
        } content: { observer in
            ScrollView {
                Group {
                    switch observer.phase {
                    case .data(let rows):
                        BookReadersSection(readers: rows, showsHeading: false)
                    case .loading:
                        ProgressView()
                            .frame(maxWidth: .infinity, minHeight: 160)
                    default:
                        ContentUnavailableView(String(localized: "book.detail_readers_empty"), systemImage: "person.2")
                    }
                }
                .padding()
            }
            .background(Color(.systemBackground))
            .navigationTitle(String(localized: "book.detail_readers"))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
