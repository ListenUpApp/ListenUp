import SwiftUI

/// Everyone reading a book, uncapped: where Book Detail's "See All N Readers" leads (spec §2.10).
struct BookReadersListView: View {
    let bookId: String

    @Environment(\.dependencies) private var deps
    @State private var observer: BookReadersObserver?

    var body: some View {
        ScrollView {
            Group {
                switch observer?.phase {
                case .data(let rows):
                    BookReadersSection(readers: rows, showsHeading: false)
                case .loading, .none:
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
        .task(id: bookId) {
            guard observer == nil else { return }
            observer = BookReadersObserver(viewModel: deps.createBookReadersViewModel(bookId: bookId))
        }
    }
}
