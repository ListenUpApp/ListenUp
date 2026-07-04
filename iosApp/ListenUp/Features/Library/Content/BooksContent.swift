import SwiftUI
import Shared

/// Content view for the Books tab in the Library.
///
/// Features:
/// - Adaptive grid: 2 columns on iPhone, 3-4 on iPad
/// - Section headers (A, B, C...) with alphabet scrubber
/// - Floating sort button
/// - Pull-to-refresh
/// - Loading, empty, and error states
struct BooksContent: View {
    let books: [BookRow]
    let bookProgress: [String: Float]
    let sortState: SortState?
    let isLoading: Bool
    let isEmpty: Bool
    let errorMessage: String?
    let onCategorySelected: (SortCategory) -> Void
    let onDirectionToggle: () -> Void
    /// Title-sort article handling — the shared toggle state + its flip action. When sorting by
    /// Title, "The Hobbit" groups under H (ignoring the article); the section letters honor it too.
    let ignoreTitleArticles: Bool
    let onToggleIgnoreArticles: () -> Void
    let onRefresh: () -> Void
    /// Drives multi-select on the grid. When `isSelecting`, taps toggle selection instead of
    /// navigating; a long-press is the secondary entry into selection mode.
    let selection: BookSelectionObserver

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var isScrolling = false
    @State private var scrollTarget: String?
    @State private var sections: [(letter: Character, books: [BookRow])] = []

    private var layout: BooksLayout {
        BooksLayout.forRegularWidth(horizontalSizeClass == .regular)
    }

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: 150), spacing: layout.gridSpacing)]
    }

    /// A letter group wrapped as `Identifiable` — the grid's outer `ForEach` needs stable per-section
    /// identity, and the underlying `sections` labeled-tuple can't provide a key path.
    private struct LetterSection: Identifiable {
        let letter: Character
        let books: [BookRow]
        var id: Character { letter }
    }

    private var letterSections: [LetterSection] {
        sections.map { LetterSection(letter: $0.letter, books: $0.books) }
    }

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

        // ONE `LazyVGrid` (a single lazy container) with a `Section` per letter — NOT the ~26 nested
        // per-section grids that made the scrubber's `scrollTo` hang the main thread on a large
        // library (#alphabet-scrubber-hang). A single lazy container converges. Keying the `ForEach`
        // on individual books (native `BookRow` value types, never bridged Kotlin objects) is what
        // lets SwiftUI diff and animate books in/out as sync adds or removes them; each section header
        // carries its `.id` anchor so the scrubber's `scrollTo` still lands on the letter.
        return ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: layout.gridSpacing) {
                    sortHeader

                    LazyVGrid(columns: columns, alignment: .leading, spacing: layout.gridSpacing) {
                        ForEach(letterSections) { section in
                            Section {
                                ForEach(section.books) { book in
                                    bookCell(book)
                                        .transition(bookTransition)
                                }
                            } header: {
                                sectionHeader(section.letter)
                            }
                        }
                    }
                }
                .padding(.horizontal, layout.sideMargin)
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
            .task(id: scrollTarget) {
                guard let target = scrollTarget else { return }
                // Instant jump, NOT animated.
                proxy.scrollTo(target, anchor: .top)
                try? await Task.sleep(for: .milliseconds(300))
                guard !Task.isCancelled else { return }   // a newer target replaced us — don't stomp it
                scrollTarget = nil
            }
            // Alphabet scrubber overlay
            .overlay(alignment: .trailing) {
                if layout.showsScrubber, shouldShowAlphabetIndex {
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
            .onChange(of: books, initial: true) { _, newBooks in
                // Animate only a genuine add/remove on an already-populated grid — never the first
                // load (all books arriving at once) or when Reduce Motion is on.
                let animate = !reduceMotion && !sections.isEmpty && !newBooks.isEmpty
                withAnimation(animate ? .snappy : nil) {
                    sections = bookSections(from: newBooks, ignoreArticles: ignoreTitleArticles)
                }
            }
            .onChange(of: ignoreTitleArticles) { _, _ in
                // A re-sort (article handling), not an add/remove — apply instantly, no animation.
                sections = bookSections(from: books, ignoreArticles: ignoreTitleArticles)
            }
        }
    }

    /// The sort control above the grid: an inline row at regular width, a floating pill when compact.
    /// (It used to be a top-leading overlay, which the `.page` TabView style hid behind the tab chips;
    /// as scrolling content it insets below the chips like everything else.)
    @ViewBuilder
    private var sortHeader: some View {
        if layout.usesInlineSort, sortState != nil {
            sortRow
        } else if let sortState {
            FloatingSortButton(
                sortState: sortState,
                categories: sortCategories,
                onCategorySelected: onCategorySelected,
                onDirectionToggle: onDirectionToggle,
                ignoreTitleArticles: ignoreTitleArticles,
                onToggleIgnoreArticles: onToggleIgnoreArticles
            )
            .padding(.top, 4)
        }
    }

    /// Full-width letter header for a grid `Section`, carrying the `section-<letter>` anchor the
    /// alphabet scrubber's `scrollTo` targets.
    private func sectionHeader(_ letter: Character) -> some View {
        Text(String(letter))
            .font(.title2.bold())
            .foregroundStyle(.primary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 8)
            .id("section-\(letter)")
    }

    /// Per-book insert/remove transition. Honors Reduce Motion (a plain cross-fade, no scale) per HIG.
    private var bookTransition: AnyTransition {
        reduceMotion
            ? .opacity
            : .scale(scale: 0.85).combined(with: .opacity)
    }

    /// A single grid cell. While selecting, the cover toggles selection on tap; otherwise it
    /// navigates to the detail screen and a long-press is the secondary entry into selection mode.
    @ViewBuilder
    private func bookCell(_ book: BookRow) -> some View {
        let card = BookCoverCard(
            book: book,
            progress: bookProgress[book.id],
            isSelecting: selection.isSelecting,
            isSelected: selection.isSelected(book.id)
        )
        if selection.isSelecting {
            Button { selection.toggle(book.id) } label: { card }
                .buttonStyle(.plain)
        } else {
            NavigationLink(value: BookDestination(id: book.id)) { card }
                .buttonStyle(.plain)
                .onLongPressGesture { selection.enter(book.id) }
        }
    }

    // MARK: - Sort Row

    private var sortRow: some View {
        let count = String(format: String(localized: "library.title_count"), books.count)
        let sortLabel = sortState?.category.label ?? ""
        return SortRow(count: count, sortLabel: sortLabel) {
            ForEach(sortCategories, id: \.rawValue) { cat in
                Button {
                    onCategorySelected(cat)
                } label: {
                    HStack {
                        Text(cat.label)
                        if cat == sortState?.category {
                            Image(systemName: "checkmark")
                        }
                    }
                }
            }
            Divider()
            Button {
                onDirectionToggle()
            } label: {
                Label(
                    sortState?.direction == .ascending
                        ? String(localized: "library.sort_ascending")
                        : String(localized: "library.sort_descending"),
                    systemImage: sortState?.direction == .ascending ? "arrow.up" : "arrow.down"
                )
            }
            if sortState?.category == .title {
                Divider()
                Toggle(isOn: Binding(get: { ignoreTitleArticles }, set: { _ in onToggleIgnoreArticles() })) {
                    Text(String(localized: "library.ignore_articles"))
                }
            }
        }
        .haptic(.selectionTick, trigger: sortState)
    }

    /// Only show alphabet index when sorted by title
    private var shouldShowAlphabetIndex: Bool {
        sortState?.category == .title
    }

    // MARK: - Loading State

    private var loadingGrid: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 20) {
                ForEach(0 ..< 8, id: \.self) { _ in
                    BookCoverShimmer()
                }
            }
            .padding(.vertical)
            .padding(.horizontal, layout.sideMargin)
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
