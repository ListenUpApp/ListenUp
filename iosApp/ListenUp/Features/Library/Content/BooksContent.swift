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
    let onRefresh: () -> Void
    /// Drives multi-select on the grid. When `isSelecting`, taps toggle selection instead of
    /// navigating; a long-press is the secondary entry into selection mode.
    let selection: BookSelectionObserver

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var isScrolling = false
    @State private var scrollTarget: String?
    @State private var sections: [(letter: Character, books: [BookRow])] = []

    private var layout: BooksLayout {
        BooksLayout.forRegularWidth(horizontalSizeClass == .regular)
    }

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: 150), spacing: layout.gridSpacing)]
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

        return GeometryReader { geo in
            // The grid is a FLAT `LazyVStack` of a letter header + manually-chunked book rows — NOT a
            // `LazyVGrid` nested inside the `LazyVStack`. That nesting made the scrubber's `scrollTo`
            // spin the main thread forever on a large library: resolving the target offset across ~26
            // nested lazy grids is a layout pass that never converges. One lazy container converges,
            // exactly like the Series/Contributor tabs. The column count (derived from width here)
            // replaces the old `GridItem(.adaptive(minimum: 150))` flow. See #alphabet-scrubber-hang.
            let columnCount = gridColumnCount(forWidth: geo.size.width)
            let cellWidth = gridCellWidth(forWidth: geo.size.width, columns: columnCount)
            let rows = bookRows(columns: columnCount)

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: layout.gridSpacing) {
                        if layout.usesInlineSort, sortState != nil {
                            sortRow
                        } else {
                            // Spacer for the floating sort button.
                            Color.clear.frame(height: 32)
                        }

                        ForEach(rows) { row in
                            rowView(row, cellWidth: cellWidth)
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
                // Sort button overlay
                .overlay(alignment: .topLeading) {
                    if !layout.usesInlineSort, let sortState {
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
                    sections = bookSections(from: books)
                }
            }
        }
    }

    /// One element of the flattened Books grid: a letter header, or a row of up-to-`columns` cells.
    /// Flattening to a single lazy container (instead of `LazyVGrid` nested in `LazyVStack`) is what
    /// stops the alphabet scrubber's `scrollTo` from hanging the main thread on a large library.
    private enum BooksGridRow: Identifiable {
        case header(Character)
        case cells(letter: Character, index: Int, books: [BookRow])

        var id: String {
            switch self {
            case .header(let letter): return "section-\(letter)"
            case .cells(let letter, let index, _): return "row-\(letter)-\(index)"
            }
        }
    }

    /// Width-driven column count, mirroring the previous `GridItem(.adaptive(minimum: 150))` flow.
    private func gridColumnCount(forWidth width: CGFloat) -> Int {
        let usable = width - layout.sideMargin * 2
        guard usable > 0 else { return 1 }
        let minCell: CGFloat = 150
        return max(1, Int((usable + layout.gridSpacing) / (minCell + layout.gridSpacing)))
    }

    /// Exact cell width so a short final row's cards keep their column width (left-aligned) instead
    /// of stretching — the manual-grid equivalent of `LazyVGrid`'s fixed tracks.
    private func gridCellWidth(forWidth width: CGFloat, columns: Int) -> CGFloat {
        guard columns > 0 else { return max(0, width) }
        let usable = width - layout.sideMargin * 2 - CGFloat(columns - 1) * layout.gridSpacing
        return max(0, usable / CGFloat(columns))
    }

    /// Flatten `sections` into header + chunked-row items so the grid renders in ONE lazy container.
    private func bookRows(columns: Int) -> [BooksGridRow] {
        guard columns > 0 else { return [] }
        var rows: [BooksGridRow] = []
        for section in sections {
            rows.append(.header(section.letter))
            var index = 0
            var start = 0
            while start < section.books.count {
                let end = min(start + columns, section.books.count)
                rows.append(.cells(letter: section.letter, index: index, books: Array(section.books[start ..< end])))
                start = end
                index += 1
            }
        }
        return rows
    }

    @ViewBuilder
    private func rowView(_ row: BooksGridRow, cellWidth: CGFloat) -> some View {
        switch row {
        case .header(let letter):
            Text(String(letter))
                .font(.title2.bold())
                .foregroundStyle(.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 8)
        case .cells(_, _, let books):
            HStack(alignment: .top, spacing: layout.gridSpacing) {
                ForEach(books) { book in
                    bookCell(book)
                        .frame(width: cellWidth)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
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
                Text(String(localized: "common.direction"))
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
