import SwiftUI
@preconcurrency import Shared
import UIKit

/// Content view for the Series tab in the Library.
///
/// Features:
/// - iPhone: vertical list of standalone `SeriesRowCard` components (each its own rounded surface)
/// - iPad: 3-column `LazyVGrid` of `SeriesGridCard` components
/// - Inline `SortRow` (Name, Book Count, Added)
/// - Alphabet scrubber when sorted by name
/// - Empty state when no series
struct SeriesContent: View {
    let seriesList: [SeriesWithBooks_]
    let seriesProgress: [String: SeriesProgressState]
    let sortState: SortState?
    let onCategorySelected: (SortCategory) -> Void
    let onDirectionToggle: () -> Void

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
            ForEach(sortCategories, id: \.name) { cat in
                Button {
                    UISelectionFeedbackGenerator().selectionChanged()
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
                UISelectionFeedbackGenerator().selectionChanged()
                onDirectionToggle()
            } label: {
                Text(String(localized: "common.direction"))
            }
        }
    }

    // MARK: - iPhone List

    private var iPhoneList: some View {
        LazyVStack(spacing: 12) {
            ForEach(Array(seriesList.enumerated()), id: \.offset) { _, seriesWithBooks in
                let seriesId = String(describing: seriesWithBooks.series.id)
                SeriesRowCard(
                    series: seriesWithBooks,
                    progress: progressFor(seriesId: seriesId, series: seriesWithBooks)
                )
                .id("series-\(seriesId)")
            }
        }
        .padding(.horizontal, 16)
    }

    // MARK: - iPad Grid

    private var iPadGrid: some View {
        let columns = [
            GridItem(.flexible()),
            GridItem(.flexible()),
            GridItem(.flexible())
        ]
        return LazyVGrid(columns: columns, spacing: 22) {
            ForEach(Array(seriesList.enumerated()), id: \.offset) { _, seriesWithBooks in
                let seriesId = String(describing: seriesWithBooks.series.id)
                SeriesGridCard(
                    series: seriesWithBooks,
                    progress: progressFor(seriesId: seriesId, series: seriesWithBooks)
                )
                .id("series-\(seriesId)")
            }
        }
        .padding(.horizontal, horizontalMargin)
    }

    // MARK: - Helpers

    private func progressFor(seriesId: String, series: SeriesWithBooks_) -> SeriesProgressState {
        seriesProgress[seriesId] ?? SeriesProgressState(finishedCount: 0, totalCount: Int(series.books.count))
    }

    private var shouldShowAlphabetIndex: Bool {
        sortState?.category == .name
    }

    /// Builds alphabet index mapping letters to first series ID for that letter.
    private func buildAlphabetIndex() -> [(letter: String, firstId: String)] {
        guard sortState?.category == .name else { return [] }

        var index: [(letter: String, firstId: String)] = []
        var seenLetters: Set<String> = []

        for seriesWithBooks in seriesList {
            let name = seriesWithBooks.series.name
            guard let firstChar = name.first else { continue }
            let letter = firstChar.isLetter ? String(firstChar).uppercased() : "#"

            if !seenLetters.contains(letter) {
                seenLetters.insert(letter)
                let seriesId = String(describing: seriesWithBooks.series.id)
                index.append((letter: letter, firstId: "series-\(seriesId)"))
            }
        }

        return index
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
