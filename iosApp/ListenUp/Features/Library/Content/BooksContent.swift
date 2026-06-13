import SwiftUI
@preconcurrency import Shared

/// Content view for the Books tab in the Library.
///
/// Features:
/// - Adaptive grid: 2 columns on iPhone, 3-4 on iPad
/// - Section headers (A, B, C...) with alphabet scrubber
/// - Floating sort button
/// - Pull-to-refresh
/// - Loading, empty, and error states
struct BooksContent: View {
    let books: [BookListItem]
    let bookProgress: [String: Float]
    let sortState: SortState?
    let isLoading: Bool
    let isEmpty: Bool
    let errorMessage: String?
    let onCategorySelected: (SortCategory) -> Void
    let onDirectionToggle: () -> Void
    let onRefresh: () -> Void

    @State private var isScrolling = false
    @State private var scrollTarget: String?
    @State private var sections: [(letter: Character, books: [BookListItem])] = []

    private let columns = [GridItem(.adaptive(minimum: 150), spacing: 16)]

    /// Available sort categories for books
    private let sortCategories: [SortCategory] = [.title, .author, .duration, .year, .added, .series]

    var body: some View {
        Group {
            if isLoading {
                loadingGrid
            } else if let error = errorMessage {
                errorState(message: error)
            } else if isEmpty {
                emptyState
            } else {
                booksGrid
            }
        }
    }

    // MARK: - Books Grid

    private var booksGrid: some View {
        let letters = sections.map { String($0.letter) }

        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 20) {
                    // Spacer for sort button
                    Color.clear.frame(height: 32)

                    ForEach(sections, id: \.letter) { section in
                        let sectionId = "section-\(section.letter)"

                        VStack(alignment: .leading, spacing: 16) {
                            // Section header
                            Text(String(section.letter))
                                .font(.title2.bold())
                                .foregroundStyle(.primary)
                                .frame(maxWidth: .infinity, alignment: .leading)

                            // Books grid
                            LazyVGrid(columns: columns, spacing: 16) {
                                ForEach(section.books, id: \.idString) { book in
                                    NavigationLink(value: BookDestination(id: book.idString)) {
                                        BookCoverCard(book: book, progress: bookProgress[book.idString])
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                        .id(sectionId)
                    }
                }
                .padding(.horizontal)
                // Extra padding at bottom so content scrolls above tab bar
                .padding(.bottom, 100)
            }
            .scrollContentBackground(.hidden)
            .refreshable {
                onRefresh()
                try? await Task.sleep(for: .seconds(1))
            }
            .onScrollPhaseChange { _, newPhase in
                withAnimation(.easeOut(duration: 0.2)) {
                    isScrolling = newPhase != .idle
                }
            }
            .onChange(of: scrollTarget) { _, newTarget in
                if let target = newTarget {
                    withAnimation(.easeOut(duration: 0.25)) {
                        proxy.scrollTo(target, anchor: .top)
                    }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                        scrollTarget = nil
                    }
                }
            }
            // Alphabet scrubber overlay
            .overlay(alignment: .trailing) {
                if shouldShowAlphabetIndex {
                    SectionIndexBar(
                        letters: letters,
                        onLetterSelected: { letter in
                            scrollTarget = "section-\(letter)"
                        },
                        isVisible: isScrolling
                    )
                    .padding(.trailing, 8)
                    .padding(.vertical, 60)
                }
            }
            // Sort button overlay
            .overlay(alignment: .topLeading) {
                if let sortState {
                    FloatingSortButton(
                        sortState: sortState,
                        categories: sortCategories,
                        onCategorySelected: onCategorySelected,
                        onDirectionToggle: onDirectionToggle
                    )
                    .padding(.leading, 16)
                    .padding(.top, 8)
                }
            }
            .onChange(of: books, initial: true) { _, _ in
                sections = buildSections(books: books)
            }
        }
    }

    /// Only show alphabet index when sorted by title
    private var shouldShowAlphabetIndex: Bool {
        sortState?.category == .title
    }

    /// Groups books into alphabetically sorted sections.
    private func buildSections(books: [BookListItem]) -> [(letter: Character, books: [BookListItem])] {
        let grouped = Dictionary(grouping: books) { book -> Character in
            guard let first = book.title.first?.uppercased().first else { return "#" }
            return first.isLetter ? first : "#"
        }

        return grouped.keys
            .sorted { lhs, rhs in
                if !lhs.isLetter && rhs.isLetter { return true }
                if lhs.isLetter && !rhs.isLetter { return false }
                return lhs < rhs
            }
            .map { (letter: $0, books: grouped[$0] ?? []) }
    }

    // MARK: - Loading State

    private var loadingGrid: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 20) {
                ForEach(0 ..< 8, id: \.self) { _ in
                    BookCoverShimmer()
                }
            }
            .padding()
        }
    }

    // MARK: - Empty State

    private var emptyState: some View {
        ContentUnavailableView(
            String(localized: "library.empty_title"),
            systemImage: "books.vertical",
            description: Text(String(localized: "library.empty_description"))
        )
    }

    // MARK: - Error State

    private func errorState(message: String) -> some View {
        ContentUnavailableView {
            Label(String(localized: "library.sync_failed"), systemImage: "exclamationmark.triangle")
        } description: {
            Text(message)
        } actions: {
            PrimaryButton(title: String(localized: "library.try_again"), icon: "arrow.clockwise", action: onRefresh)
                .frame(maxWidth: 240)
        }
    }
}
