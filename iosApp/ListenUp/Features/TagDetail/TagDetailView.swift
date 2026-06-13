import SwiftUI
@preconcurrency import Shared

/// Tag detail screen — the books carrying a given tag.
///
/// A tag is simply "books with this tag," so the screen is deliberately spare: a
/// width-responsive grid of book covers under the tag's name. Columns flow from the
/// available width (`GridItem(.adaptive`), so it stays right from a narrow Split View
/// up to full-screen iPad. Tapping a cover pushes the book detail onto the shared stack.
struct TagDetailView: View {
    let tagId: String

    @Environment(\.dependencies) private var deps
    @State private var observer: TagDetailObserver?

    var body: some View {
        Group {
            if let observer {
                content(observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(observer?.tagName ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: tagId) {
            let obs = TagDetailObserver(viewModel: deps.createTagDetailViewModel())
            observer = obs
            obs.loadTag(tagId)
        }
        .onDisappear {
            observer?.stopObserving()
            observer = nil
        }
    }

    // MARK: - Content by phase

    @ViewBuilder
    private func content(_ observer: TagDetailObserver) -> some View {
        switch observer.phase {
        case .loading:
            LoadingStateView()
        case .error(let message):
            ContentUnavailableView(
                String(localized: "tag.unavailable_title"),
                systemImage: "exclamationmark.triangle",
                description: Text(message)
            )
        case .ready:
            if observer.books.isEmpty {
                ContentUnavailableView(
                    String(localized: "tag.no_books"),
                    systemImage: "tag"
                )
            } else {
                bookGrid(observer)
            }
        }
    }

    // MARK: - Grid

    private func bookGrid(_ observer: TagDetailObserver) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                Text(bookCountLabel(observer.bookCount))
                    .font(.subheadline)
                    .foregroundStyle(Color.luLabel2)
                    .frame(maxWidth: .infinity, alignment: .leading)

                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: 150), spacing: 16)],
                    spacing: 20
                ) {
                    ForEach(Array(observer.books), id: \.idString) { book in
                        NavigationLink(value: BookDestination(id: book.idString)) {
                            BookCoverCard(book: book, progress: nil)
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

    private func bookCountLabel(_ count: Int) -> String {
        let key = TagDetailSnapshot.bookCountKey(count)
        return String(format: String(localized: String.LocalizationValue(key)), count)
    }
}

#Preview {
    NavigationStack {
        TagDetailView(tagId: "preview")
    }
}
