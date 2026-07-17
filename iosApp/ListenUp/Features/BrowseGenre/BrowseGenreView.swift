import SwiftUI
import Shared

/// Browse-by-Genre screen — reached by tapping a genre chip on Book Detail.
///
/// The genre hierarchy lists as indented, selectable rows (depth from the materialized path); the
/// selected genre's books hydrate into a width-responsive cover grid. An "Include subtree" toggle
/// widens the fetch to the genre's descendants via the shared VM's path-prefix match. Columns flow
/// continuously from the available width (`GridItem(.adaptive)`), so the grid stays right from a
/// narrow Split View up to full-screen iPad.
struct BrowseGenreView: View {
    let initialGenreId: String
    let initialGenreName: String

    @Environment(\.dependencies) private var deps
    @State private var observer: BrowseGenreObserver?

    var body: some View {
        Group {
            if let observer {
                content(observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "genre.browse_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: initialGenreId) {
            let obs = BrowseGenreObserver(viewModel: deps.createBrowseGenreViewModel())
            observer = obs
            obs.select(genreId: initialGenreId)
        }
        .onDisappear {
            // Release the observer; its deinit cancels the FlowBridge subscriptions.
            observer = nil
        }
    }

    // MARK: - Content by phase

    @ViewBuilder
    private func content(_ observer: BrowseGenreObserver) -> some View {
        switch observer.phase {
        case .loading:
            LoadingStateView()
        case .error(let message):
            ContentUnavailableView(message, systemImage: "exclamationmark.triangle")
        case .ready:
            ready(observer)
        }
    }

    private func ready(_ observer: BrowseGenreObserver) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                includeSubtreeToggle(observer)

                if observer.genres.isEmpty {
                    ContentUnavailableView(
                        String(localized: "genre.no_genres_yet"),
                        systemImage: "square.stack.3d.up"
                    )
                    .frame(maxWidth: .infinity)
                    .padding(.top, 40)
                } else {
                    genreTree(observer)
                }

                if observer.selectedGenreId != nil {
                    Divider()
                    booksSection(observer)
                }
            }
            .readableWidth()
            .padding(.horizontal)
            .padding(.top, 8)
            .padding(.bottom, 100)
        }
    }

    // MARK: - Include-subtree toggle

    private func includeSubtreeToggle(_ observer: BrowseGenreObserver) -> some View {
        Toggle(
            String(localized: "genre.include_subtree"),
            isOn: Binding(
                get: { observer.includeDescendants },
                set: { _ in observer.toggleIncludeDescendants() }
            )
        )
        .font(.subheadline)
        .tint(Color.listenUpOrange)
    }

    // MARK: - Genre tree

    private func genreTree(_ observer: BrowseGenreObserver) -> some View {
        VStack(spacing: 0) {
            ForEach(observer.genres) { node in
                genreRow(node, isSelected: node.id == observer.selectedGenreId) {
                    observer.select(genreId: node.id)
                }
            }
        }
    }

    private func genreRow(_ node: GenreNodeModel, isSelected: Bool, onTap: @escaping () -> Void) -> some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                Text(node.name)
                    .font(.body)
                    .fontWeight(node.depth == 0 ? .semibold : .regular)
                    .foregroundStyle(.primary)
                Spacer(minLength: 8)
                if node.bookCount > 0 {
                    Text(BrowseGenreView.countLabel(node.bookCount))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, 10)
            .padding(.trailing, 4)
            .padding(.leading, CGFloat(node.depth) * 16)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(
            isSelected ? Color.listenUpOrange.opacity(0.12) : Color.clear,
            in: RoundedRectangle(cornerRadius: 10, style: .continuous)
        )
    }

    // MARK: - Books for the selected genre

    @ViewBuilder
    private func booksSection(_ observer: BrowseGenreObserver) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(selectedGenreName(observer))
                .font(.title3.weight(.bold))
                .foregroundStyle(.primary)

            if observer.isFetchingBooks && observer.books.isEmpty {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
            } else if observer.books.isEmpty {
                ContentUnavailableView(
                    String(localized: "genre.no_books_in_genre"),
                    systemImage: "books.vertical"
                )
            } else {
                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: 150), spacing: 16)],
                    spacing: 20
                ) {
                    ForEach(observer.books) { book in
                        NavigationLink(value: BookDestination(id: book.id)) {
                            BookCoverCard(book: book, progress: nil)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    /// The selected genre's name — from the loaded tree, falling back to the route-supplied name
    /// while the tree hydrates.
    private func selectedGenreName(_ observer: BrowseGenreObserver) -> String {
        observer.genres.first { $0.id == observer.selectedGenreId }?.name ?? initialGenreName
    }

    // MARK: - Count copy

    private static func countLabel(_ count: Int) -> String {
        count == 1
            ? String(format: String(localized: "genre.book_count"), count)
            : String(format: String(localized: "genre.books_count"), count)
    }
}

#Preview {
    NavigationStack {
        BrowseGenreView(initialGenreId: "preview", initialGenreName: "Epic Fantasy")
    }
}
