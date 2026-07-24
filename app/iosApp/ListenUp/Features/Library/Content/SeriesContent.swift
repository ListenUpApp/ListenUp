import SwiftUI
import Shared

/// Content view for the Series tab in the Library.
///
/// Features:
/// - iPhone: vertical list of standalone `SeriesRowCard` components (each its own rounded surface)
/// - iPad: 3-column `LazyVGrid` of `SeriesGridCard` components
/// - Inline `SortRow` (Name, Book Count, Added)
/// - Alphabet scrubber when sorted by name
/// - Empty state when no series
struct SeriesContent: View {
    let seriesList: [SeriesRow]
    let seriesProgress: [String: SeriesProgressState]
    let sortState: SortState?
    let onCategorySelected: (SortCategory) -> Void
    let onDirectionToggle: () -> Void
    /// Name-sort article handling — shared toggle state + flip action (Series sorts by Name). Groups
    /// "The Expanse" under E, matching the shared sort order.
    let ignoreTitleArticles: Bool
    let onToggleIgnoreArticles: () -> Void

    @Environment(\.horizontalSizeClass) private var sizeClass

    @State private var isScrolling = false
    @State private var scrollTarget: String?

    /// Available sort categories for series
    private let sortCategories: [SortCategory] = [.name, .bookCount, .added]

    /// Generous side margins at regular width (matching `SeriesPad`); phone margins at compact.
    private var horizontalMargin: CGFloat { sizeClass == .regular ? 36 : 16 }

    var body: some View {
        if seriesList.isEmpty {
            emptyState
        } else {
            seriesListView
        }
    }

    // MARK: - Series List

    private var seriesListView: some View {
        let letters = buildAlphabetIndex()

        return ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 0) {
                    sortRow
                        .padding(.horizontal, horizontalMargin)
                        .padding(.top, 4)
                        .padding(.bottom, 12)

                    if sizeClass == .compact {
                        iPhoneList
                    } else {
                        iPadGrid
                    }
                }
                .padding(.bottom, 100)
            }
            .scrollContentBackground(.hidden)
            .onScrollPhaseChange { _, newPhase in
                withAnimation(.easeOut(duration: 0.2)) {
                    isScrolling = newPhase != .idle
                }
            }
            .task(id: scrollTarget) {
                guard let target = scrollTarget else { return }
                // Instant jump, NOT animated: animating a scrollTo across a large lazy list
                // freezes the main thread on every scrubber letter-change (#alphabet-scrubber-hang).
                proxy.scrollTo(target, anchor: .top)
                try? await Task.sleep(for: .milliseconds(300))
                guard !Task.isCancelled else { return }   // a newer target replaced us — don't stomp it
                scrollTarget = nil
            }
            // Alphabet scrubber (only for name sort, compact width — the wide iPad grid omits it)
            .overlay(alignment: .trailing) {
                if sizeClass == .compact, shouldShowAlphabetIndex, !letters.isEmpty {
                    SectionIndexBar(
                        letters: letters.map { $0.letter },
                        onLetterSelected: { letter in
                            if let entry = letters.first(where: { $0.letter == letter }) {
                                scrollTarget = entry.firstId
                            }
                        },
                        isVisible: isScrolling
                    )
                    .padding(.trailing, 8)
                    .padding(.vertical, 60)
                }
            }
        }
    }

    // MARK: - Sort Row

    private var sortRow: some View {
        let count = String(format: String(localized: "library.series_count"), seriesList.count)
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
            if sortState?.category == .name {
                Divider()
                Toggle(isOn: Binding(get: { ignoreTitleArticles }, set: { _ in onToggleIgnoreArticles() })) {
                    Text(String(localized: "library.ignore_articles"))
                }
            }
        }
        .haptic(.selectionTick, trigger: sortState)
    }

    // MARK: - iPhone List

    private var iPhoneList: some View {
        LazyVStack(spacing: 12) {
            ForEach(seriesList) { row in
                SeriesRowCard(series: row, progress: progressFor(row))
                    .id("series-\(row.id)")
            }
        }
        .padding(.horizontal, 16)
    }

    // MARK: - iPad Grid

    private var iPadGrid: some View {
        // Responsive, not fixed-3-up: columns flow from the actual available width, so the
        // grid stays right across full-screen iPad, Split View, and Stage Manager widths.
        let columns = [GridItem(.adaptive(minimum: 260), spacing: 22)]
        return LazyVGrid(columns: columns, spacing: 22) {
            ForEach(seriesList) { row in
                SeriesGridCard(series: row, progress: progressFor(row))
                    .id("series-\(row.id)")
            }
        }
        .padding(.horizontal, horizontalMargin)
    }

    // MARK: - Helpers

    private func progressFor(_ row: SeriesRow) -> SeriesProgressState {
        seriesProgress[row.id] ?? SeriesProgressState(finishedCount: 0, totalCount: row.bookCount)
    }

    private var shouldShowAlphabetIndex: Bool {
        sortState?.category == .name
    }

    /// Alphabet index (letter → first series id), only when sorted by name. Pure logic lives in
    /// `seriesAlphabetIndex` over the native `SeriesRow`, so the scrubber never re-bridges.
    private func buildAlphabetIndex() -> [(letter: String, firstId: String)] {
        guard sortState?.category == .name else { return [] }
        return seriesAlphabetIndex(from: seriesList, ignoreArticles: ignoreTitleArticles)
    }

    // MARK: - Empty State

    private var emptyState: some View {
        ContentUnavailableView(
            String(format: String(localized: "common.no_items_yet"), "series"),
            systemImage: "books.vertical",
            description: Text(String(format: String(localized: "library.empty_tab_description"), "Series"))
        )
    }
}
