import SwiftUI
@preconcurrency import Shared
import UIKit

/// Content view for the Series tab in the Library.
///
/// Features:
/// - Adaptive grid of SeriesCard components
/// - Animated cover stacks that cycle through books
/// - Floating sort button (Name, Book Count, Added)
/// - Alphabet scrubber when sorted by name
/// - Empty state when no series
struct SeriesContent: View {
    let seriesList: [SeriesWithBooks_]
    let sortState: SortState?
    let onCategorySelected: (SortCategory) -> Void
    let onDirectionToggle: () -> Void

    @Environment(\.horizontalSizeClass) private var sizeClass

    @State private var isScrolling = false
    @State private var scrollTarget: String?

    /// Single column on iPhone (compact), adaptive grid on iPad (regular)
    private var columns: [GridItem] {
        if sizeClass == .compact {
            [GridItem(.flexible())]
        } else {
            [GridItem(.adaptive(minimum: 200), spacing: 16)]
        }
    }

    /// Available sort categories for series
    private let sortCategories: [SortCategory] = [.name, .bookCount, .added]

    var body: some View {
        if seriesList.isEmpty {
            emptyState
        } else {
            seriesGrid
        }
    }

    // MARK: - Series Grid

    private var seriesGrid: some View {
        let letters = buildAlphabetIndex()

        return ScrollViewReader { proxy in
            ScrollView {
                LazyVGrid(columns: columns, spacing: 16) {
                    // Spacer for sort button
                    Color.clear
                        .frame(height: 32)
                        .gridCellUnsizedAxes(.horizontal)

                    ForEach(Array(seriesList.enumerated()), id: \.offset) { _, seriesWithBooks in
                        let seriesId = String(describing: seriesWithBooks.series.id)
                        SeriesCard(series: seriesWithBooks)
                            .id("series-\(seriesId)")
                    }
                }
                .padding(.horizontal)
                // Extra padding at bottom so content scrolls above tab bar
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
            // Alphabet scrubber (only for name sort)
            .overlay(alignment: .trailing) {
                if shouldShowAlphabetIndex, !letters.isEmpty {
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
            // Sort button
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
        }
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
        VStack(spacing: 16) {
            Spacer()

            Image(systemName: "books.vertical")
                .font(.system(size: 64))
                .foregroundStyle(.secondary)

            Text(String(format: String(localized: "common.no_items_yet"), "series"))
                .font(.title2.bold())

            Text(String(format: String(localized: "library.empty_tab_description"), "Series"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            Spacer()
        }
        .frame(maxWidth: .infinity)
    }
}
