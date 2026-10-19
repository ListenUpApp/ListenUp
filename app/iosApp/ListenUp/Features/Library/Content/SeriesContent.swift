import SwiftUI
import Shared

/// Content view for the Series tab in the Library.
///
/// Features:
/// - iPhone: vertical list of standalone `SeriesRowCard` components (each its own rounded surface)
/// - iPad / wide: width-responsive `LazyVGrid` of `SeriesGridCard` components (columns flow from
///   the available width via `GridItem(.adaptive(minimum:))`, not a fixed 3-up)
/// - Under a name sort, a lettered header per shared letter run (spec L25), pinned while its series
///   scroll past, as the Authors and Narrators lists have, plus the section index
/// - Empty state when no series
struct SeriesContent: View {
    /// The series, sliced by the shared letter runs: one lettered section per run on a name sort, one
    /// unlettered section on any other. Sliced once per content revision by `LibraryObserver`.
    let sections: [LetterRun.Section<SeriesRow>]
    let seriesProgress: [String: SeriesProgressState]
    /// The shared letter runs over the series (name sort only, article-aware so "The Expanse" files
    /// under E), bridged once per content revision by `LibraryObserver` rather than on every render
    /// of this body (2026-09-29 iOS audit, performance). Empty on any other sort: no index.
    let letters: [LetterRun]
    /// The section switcher, shown as the first row in every state; `nil` in the iPad sidebar.
    var picker: LibrarySectionPicker?

    @Environment(\.horizontalSizeClass) private var sizeClass

    @State private var isScrolling = false
    @State private var pendingJump: String?

    /// Generous side margins at regular width (matching `SeriesPad`); phone margins at compact.
    private var horizontalMargin: CGFloat { sizeClass == .regular ? 36 : 16 }

    /// A name sort: the sections carry letters to head them.
    nonisolated static func isLettered(_ sections: [LetterRun.Section<SeriesRow>]) -> Bool {
        sections.first.map { !$0.letter.isEmpty } ?? false
    }

    /// Where an index letter jumps: the first series under it, by the id each card scrolls to.
    nonisolated static func scrollTarget(
        forLetter letter: String,
        in sections: [LetterRun.Section<SeriesRow>]
    ) -> String? {
        sections.first { $0.letter == letter }?.items.first.map { "series-\($0.id)" }
    }

    private var isLettered: Bool { Self.isLettered(sections) }

    var body: some View {
        if sections.isEmpty {
            emptyState
        } else {
            seriesListView
        }
    }

    // MARK: - Series List

    private var seriesListView: some View {
        let indexLetters = LetterRun.railLetters(letters)

        return ScrollViewReader { proxy in
            ScrollView {
                picker?.headerRow(horizontalMargin: horizontalMargin)
                Group {
                    if sizeClass == .compact {
                        iPhoneList
                    } else {
                        iPadGrid
                    }
                }
                .padding(.top, Spacing.xxs)
            }
            .scrollContentBackground(.hidden)
            .onScrollPhaseChange { _, newPhase in
                withAnimation(.easeOut(duration: 0.2)) {
                    isScrolling = newPhase != .idle
                }
            }
            .task(id: pendingJump) {
                guard let target = pendingJump else { return }
                // Instant jump, NOT animated: animating a scrollTo across a large lazy list
                // freezes the main thread on every scrubber letter-change (#alphabet-scrubber-hang).
                proxy.scrollTo(target, anchor: .top)
                try? await Task.sleep(for: .milliseconds(300))
                guard !Task.isCancelled else { return }   // a newer target replaced us — don't stomp it
                pendingJump = nil
            }
            // Alphabet scrubber (name sort only), at every width — see `BooksLayout.showsScrubber`.
            .overlay(alignment: .trailing) {
                if !indexLetters.isEmpty {
                    SectionIndexBar(
                        letters: indexLetters,
                        onLetterSelected: { letter in
                            if let target = Self.scrollTarget(forLetter: letter, in: sections) {
                                pendingJump = target
                            }
                        },
                        isVisible: isScrolling
                    )
                    .padding(.trailing, Spacing.xs)
                    .padding(.vertical, 60)
                }
            }
        }
    }

    // MARK: - iPhone List

    private var iPhoneList: some View {
        LazyVStack(spacing: 12, pinnedViews: isLettered ? [.sectionHeaders] : []) {
            ForEach(sections) { group in
                Section {
                    ForEach(group.items) { row in
                        SeriesRowCard(series: row, progress: progressFor(row))
                            .id("series-\(row.id)")
                    }
                } header: {
                    letterHeader(group.letter)
                }
            }
        }
        .padding(.horizontal, Spacing.m)
    }

    // MARK: - iPad Grid

    private var iPadGrid: some View {
        // Responsive, not fixed-3-up: columns flow from the actual available width, so the
        // grid stays right across full-screen iPad, Split View, and Stage Manager widths.
        let columns = [GridItem(.adaptive(minimum: 260), spacing: 22)]
        return LazyVGrid(columns: columns, spacing: 22, pinnedViews: isLettered ? [.sectionHeaders] : []) {
            ForEach(sections) { group in
                Section {
                    ForEach(group.items) { row in
                        SeriesGridCard(series: row, progress: progressFor(row))
                            .id("series-\(row.id)")
                    }
                } header: {
                    letterHeader(group.letter)
                }
            }
        }
        .padding(.horizontal, horizontalMargin)
    }

    /// The run's letter on a name sort, on the surface so it stays legible when pinned; nothing otherwise.
    @ViewBuilder
    private func letterHeader(_ letter: String) -> some View {
        if isLettered {
            LetterHeader(letter: letter)
                .background(Color.luSurface)
        }
    }

    // MARK: - Helpers

    private func progressFor(_ row: SeriesRow) -> SeriesProgressState {
        seriesProgress[row.id] ?? SeriesProgressState(finishedCount: 0, totalCount: row.bookCount)
    }

    // MARK: - Empty State

    private var emptyState: some View {
        LibrarySectionState(picker: picker) {
            ContentUnavailableView(
                String(format: String(localized: "common.no_items_yet"), "series"),
                systemImage: "books.vertical",
                description: Text(String(format: String(localized: "library.empty_tab_description"), "Series"))
            )
        }
    }
}
