import SwiftUI
import Shared

/// Content view for the Books tab in the Library.
///
/// Features:
/// - Adaptive grid: 2 columns on iPhone, 3-4 on iPad
/// - Section headers (A, B, C...) with alphabet scrubber
/// - Pull-to-refresh (sorting lives in the Library toolbar's Sort menu)
/// - Loading, empty, and error states
struct BooksContent: View {
    let books: [BookRow]
    let bookProgress: [String: Float]
    /// What each book's card says about where the reader is with it (spec §2.6).
    var bookStatus: [String: LibraryCardState] = [:]
    let sortState: SortState?
    let isLoading: Bool
    /// The library itself has no books.
    let isEmpty: Bool
    /// The library has books, but `statusFilter` matches none of them.
    var isFilteredEmpty = false
    var statusFilter: BookStatusFilter = .all
    /// Clears the status filter from the filtered-empty state.
    var onShowAll: () -> Void = {}
    let errorMessage: String?
    /// Title-sort article handling. When sorting by Title, "The Hobbit" groups under H (ignoring the
    /// article); the section letters honor it too.
    let ignoreTitleArticles: Bool
    let onRefresh: () -> Void
    /// Drives multi-select on the grid. When `isSelecting`, taps toggle selection instead of
    /// navigating; a long-press is the secondary entry into selection mode.
    let selection: BookSelectionObserver
    /// The section switcher, shown as the first row in every state; `nil` in the iPad sidebar.
    var picker: LibrarySectionPicker?
    /// The admin inbox entry, the Books section's first row after the picker; nil when nothing is
    /// held, for a member, while selecting, or in the other sections.
    var inbox: LibraryInboxEntryModel?

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

    var body: some View {
        Group {
            if isLoading {
                loadingGrid
            } else if let error = errorMessage {
                errorState(message: error)
            } else if isEmpty {
                emptyState
            } else if isFilteredEmpty {
                filteredEmptyState
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
                picker?.headerRow(horizontalMargin: layout.sideMargin)
                if let inbox {
                    LibraryInboxEntry(model: inbox)
                        .padding(.horizontal, layout.sideMargin)
                        .padding(.bottom, Spacing.s)
                }
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
                .padding(.horizontal, layout.sideMargin)
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
                    .padding(.trailing, Spacing.xs)
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

    /// Full-width letter header for a grid `Section`, carrying the `section-<letter>` anchor the
    /// alphabet scrubber's `scrollTo` targets.
    private func sectionHeader(_ letter: Character) -> some View {
        Text(String(letter))
            .font(.title2.bold())
            .foregroundStyle(.primary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, Spacing.xs)
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
            isSelected: selection.isSelected(book.id),
            libraryState: bookStatus[book.id],
            showsNarrator: true
        )
        if selection.isSelecting {
            Button { selection.toggle(book.id) } label: { card }
                .buttonStyle(.plain)
        } else {
            // A plain value-based NavigationLink: a tap opens the book. A long-press opens the book's
            // context menu (Play, Add to Shelf, Share, Select), which iOS arbitrates against the
            // link's own tap so the two never double-fire.
            NavigationLink(value: BookDestination(id: book.id)) {
                card.heroSource(bookCoverHeroID(book.id))
            }
                .buttonStyle(.plain)
                .draggableBookCover(book)
                .bookContextMenu(bookId: book.id, selection: selection) { card }
        }
    }

    /// Only show alphabet index when sorted by title
    private var shouldShowAlphabetIndex: Bool {
        sortState?.category == .title
    }

    // MARK: - Loading State

    private var loadingGrid: some View {
        ScrollView {
            picker?.headerRow(horizontalMargin: layout.sideMargin)
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
        LibrarySectionState(picker: picker) {
            VStack(spacing: Spacing.m) {
                if let inbox {
                    LibraryInboxEntry(model: inbox)
                        .padding(.horizontal, Spacing.m)
                }
                ContentUnavailableView(
                    String(localized: "library.empty_title"),
                    systemImage: "books.vertical",
                    description: Text(String(localized: "library.empty_description"))
                )
            }
        }
    }

    // MARK: - Filtered-Empty State

    /// The library has books, but the status filter matches none — never "your library is empty".
    private var filteredEmptyState: some View {
        LibrarySectionState(picker: picker) {
            VStack(spacing: Spacing.m) {
                if let inbox {
                    LibraryInboxEntry(model: inbox)
                        .padding(.horizontal, Spacing.m)
                }
                ContentUnavailableView {
                    Text(LibrarySubtitle.filteredEmpty(statusFilter))
                } actions: {
                    Button(String(localized: "library.show_all_books")) { onShowAll() }
                }
            }
        }
    }

    // MARK: - Error State

    private func errorState(message: String) -> some View {
        LibrarySectionState(picker: picker) {
            errorContent(message: message)
        }
    }

    private func errorContent(message: String) -> some View {
        ContentUnavailableView {
            Label(String(localized: "library.sync_failed"), systemImage: "exclamationmark.triangle")
        } description: {
            Text(message)
        } actions: {
            Button(action: onRefresh) {
                ActionLabel(title: String(localized: "common.try_again"), systemImage: "arrow.clockwise")
            }
            .prominentAction()
            .frame(maxWidth: 240)
        }
    }
}
