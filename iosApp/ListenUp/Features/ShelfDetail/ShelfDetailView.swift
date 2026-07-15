import SwiftUI
import Shared

/// Shelf detail screen — the books a user has curated onto one shelf.
///
/// A shelf is "the books I put here," so the screen is deliberately spare: the shelf's
/// optional description and book count over a width-responsive grid of book covers.
/// Columns flow from the available width (`GridItem(.adaptive`), so it stays right from a
/// narrow Split View up to full-screen iPad. Tapping a cover pushes the book detail onto
/// the shared stack.
struct ShelfDetailView: View {
    let shelfId: String

    @Environment(\.dependencies) private var deps
    @State private var observer: ShelfDetailObserver?
    @State private var showEdit: Bool = false

    var body: some View {
        Group {
            if let observer {
                content(observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(observer?.shelfName ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(String(localized: "common.edit")) { showEdit = true }
            }
        }
        .sheet(isPresented: $showEdit) {
            CreateEditShelfView(shelfId: shelfId)
        }
        .task(id: shelfId) {
            let obs = ShelfDetailObserver(viewModel: deps.createShelfDetailViewModel())
            observer = obs
            obs.loadShelf(shelfId)
        }
        .onDisappear {
            // Release the observer; its deinit cancels the FlowBridge subscriptions.
            observer = nil
        }
    }

    // MARK: - Content by phase

    @ViewBuilder
    private func content(_ observer: ShelfDetailObserver) -> some View {
        switch observer.phase {
        case .loading:
            LoadingStateView()
        case .error(let message):
            ContentUnavailableView(
                String(localized: "shelf.unavailable_title"),
                systemImage: "exclamationmark.triangle",
                description: Text(message)
            )
        case .ready:
            if observer.books.isEmpty {
                ContentUnavailableView(
                    String(localized: "shelf.no_books_title"),
                    systemImage: "bookmark",
                    description: Text(String(localized: "shelf.no_books_description"))
                )
            } else {
                bookGrid(observer)
            }
        }
    }

    // MARK: - Grid

    private func bookGrid(_ observer: ShelfDetailObserver) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                header(observer)

                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: 150), spacing: 16)],
                    spacing: 20
                ) {
                    ForEach(observer.books, id: \.id) { book in
                        NavigationLink(value: BookDestination(id: book.id)) {
                            ShelfBookCoverCard(book: book)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .readableWidth()
            .padding(.horizontal)
            .padding(.bottom, 100)
        }
    }

    private func header(_ observer: ShelfDetailObserver) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            if let description = observer.shelfDescription, !description.isEmpty {
                Text(description)
                    .font(.subheadline)
                    .foregroundStyle(Color.luLabel2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            Text(bookCountLabel(observer.bookCount))
                .font(.subheadline)
                .foregroundStyle(Color.luLabel2)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func bookCountLabel(_ count: Int) -> String {
        let key = ShelfDetailSnapshot.bookCountKey(count)
        return String(format: String(localized: String.LocalizationValue(key)), count)
    }
}

/// A single book on a shelf — cover over title + authors.
///
/// `ShelfBookRow` carries only display-critical fields (id, title, authors, cover path), so this
/// is a slim sibling of `BookCoverCard` (which needs a full `BookListItem`). Cover loading,
/// auth, and gradient placeholders are reused via `BookCoverImage`.
private struct ShelfBookCoverCard: View {
    let book: ShelfBookRow

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            BookCoverImage(bookId: book.id, coverPath: book.coverPath)
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .shadow(color: .black.opacity(0.15), radius: 8, x: 0, y: 4)

            VStack(alignment: .leading, spacing: 2) {
                Text(book.title)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .foregroundStyle(.primary)

                if !book.authorNames.isEmpty {
                    Text(book.authorNames.joined(separator: ", "))
                        .font(.caption)
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    NavigationStack {
        ShelfDetailView(shelfId: "preview")
    }
}
