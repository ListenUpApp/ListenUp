import SwiftUI
import Shared

/// Content view for the Series tab in the Library.
///
/// Features:
/// - iPhone: vertical list of standalone `SeriesRowCard` components (each its own rounded surface)
/// - iPad / wide: width-responsive `LazyVGrid` of `SeriesGridCard` components (columns flow from
///   the available width via `GridItem(.adaptive(minimum:))`, not a fixed 3-up)
/// - Alphabet scrubber when sorted by name
/// - Empty state when no series
struct SeriesContent: View {
    let seriesList: [SeriesRow]
    let seriesProgress: [String: SeriesProgressState]
    let sortState: SortState?
    /// The scrubber's letters (name sort only, article-aware so "The Expanse" files under E), built
    /// once per content change by `LibraryObserver` rather than on every render of this body
    /// (2026-09-29 iOS audit, performance).
    let letterIndex: [(letter: String, firstId: String)]

    @Environment(\.horizontalSizeClass) private var sizeClass

    @State private var isScrolling = false
    @State private var scrollTarget: String?

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
        let letters = letterIndex

        return ScrollViewReader { proxy in
            ScrollView {
                Group {
                    if sizeClass == .compact {
                        iPhoneList
                    } else {
                        iPadGrid
                    }
                }
                .padding(.top, 4)
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
            // Alphabet scrubber (name sort only), at every width — see `BooksLayout.showsScrubber`.
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
        }
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

    // MARK: - Empty State

    private var emptyState: some View {
        ContentUnavailableView(
            String(format: String(localized: "common.no_items_yet"), "series"),
            systemImage: "books.vertical",
            description: Text(String(format: String(localized: "library.empty_tab_description"), "Series"))
        )
    }
}
