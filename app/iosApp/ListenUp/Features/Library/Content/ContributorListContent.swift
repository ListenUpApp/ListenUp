import SwiftUI
import Shared

/// A single-role contributor Library tab (Authors or Narrators): the observer's cached letter
/// sections, with a width-driven layout and an alphabet scrubber on name sort. The role is supplied
/// by the caller so the same view backs both tabs.
///
/// - Narrower than two columns: a system inset-grouped `List`, a section per letter. Each person is
///   a lazily built row with the system highlight and disclosure indicator (HIG, Lists and tables).
/// - Wider: a `LazyVGrid` whose column count flows from the width, the letters as pinned section
///   headers, each person a card — a collection of people rather than a stretched list (HIG,
///   Collections; iosApp rule 12).
///
/// Both are lazy per row: the old hand-drawn grouped cards built every person in a letter (or, on a
/// non-name sort, every person) at once (2026-09-29 iOS audit, performance).
struct ContributorListContent: View {
    let sections: [ContributorLetterGrouping.Group]
    let sortState: SortState?
    let roleKind: RoleChip.Kind

    @State private var isScrolling = false
    @State private var scrollTarget: String?
    /// Available list width, read non-intrusively to drive the responsive column count. Read via
    /// `onGeometryChange` rather than a greedy `GeometryReader`.
    @State private var listWidth: CGFloat = 0

    private var isNameSort: Bool { sortState?.category == .name }
    private var isAuthors: Bool { roleKind == .author }
    /// Scrubber letters are the rendered sections' own letters, so the two can never drift.
    private var scrubberLetters: [String] { isNameSort ? sections.map(\.letter) : [] }

    var body: some View {
        if sections.isEmpty {
            emptyState
        } else {
            listBody
        }
    }

    private var listBody: some View {
        let columns = ContributorColumns.columnCount(availableWidth: listWidth)
        return ScrollViewReader { proxy in
            Group {
                if columns <= 1 {
                    list
                } else {
                    grid(columns: columns)
                }
            }
            .onScrollPhaseChange { _, newPhase in
                withAnimation(.easeOut(duration: 0.2)) { isScrolling = newPhase != .idle }
            }
            .task(id: scrollTarget) {
                guard let target = scrollTarget else { return }
                // Instant jump, NOT animated: animating a scrollTo across a large lazy list
                // forces SwiftUI to lay out the whole intervening range to run the animation,
                // freezing the main thread on every scrubber letter-change. Native section
                // indexes jump instantly (#alphabet-scrubber-hang).
                proxy.scrollTo(target, anchor: .top)
                try? await Task.sleep(for: .milliseconds(300))
                guard !Task.isCancelled else { return }   // a newer target replaced us — don't stomp it
                scrollTarget = nil
            }
            .overlay(alignment: .trailing) {
                if !scrubberLetters.isEmpty {
                    SectionIndexBar(
                        letters: scrubberLetters,
                        onLetterSelected: { scrollTarget = Self.scrollTarget(forLetter: $0, in: sections) },
                        isVisible: isScrolling
                    )
                    .padding(.trailing, Spacing.xs)
                    .padding(.vertical, 60)
                }
            }
        }
        .onGeometryChange(for: CGFloat.self, of: { $0.size.width }, action: { listWidth = $0 })
    }

    /// Where a scrubber letter jumps: the first person under it. Rows, not section headers, are what
    /// a `List`'s `scrollTo` reliably resolves.
    nonisolated static func scrollTarget(forLetter letter: String, in sections: [ContributorLetterGrouping.Group]) -> String? {
        sections.first { $0.letter == letter }?.items.first?.id
    }

    private var list: some View {
        List {
            ForEach(sections, id: \.letter) { group in
                Section {
                    ForEach(group.items) { person in
                        PersonRow(contributor: person, kind: roleKind)
                    }
                } header: {
                    if isNameSort { Text(group.letter) }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private func grid(columns: Int) -> some View {
        ScrollView {
            LazyVGrid(
                columns: Array(repeating: GridItem(.flexible(), spacing: 16, alignment: .top), count: columns),
                alignment: .leading,
                spacing: 12,
                pinnedViews: isNameSort ? [.sectionHeaders] : []
            ) {
                ForEach(sections, id: \.letter) { group in
                    Section {
                        ForEach(group.items) { person in
                            PersonRow(contributor: person, kind: roleKind, style: .card)
                                .id(person.id)
                        }
                    } header: {
                        if isNameSort {
                            LetterHeader(letter: group.letter)
                                .background(Color.luSurface)
                        }
                    }
                }
            }
            .padding(.horizontal, Spacing.xxl)
            .padding(.bottom, Spacing.xl)
        }
        .scrollContentBackground(.hidden)
        .background(Color.luSurface)
    }

    private var emptyState: some View {
        ScrollView {
            ContentUnavailableView(
                String(localized: "library.contributors_empty"),
                systemImage: isAuthors ? "person.fill" : "waveform.circle.fill",
                description: Text(String(
                    format: String(localized: "library.empty_tab_description"),
                    String(localized: isAuthors ? "library.authors" : "library.narrators")
                ))
            )
            .frame(maxWidth: .infinity, minHeight: 360)
        }
        .scrollContentBackground(.hidden)
        .background(Color.luSurface)
    }
}
