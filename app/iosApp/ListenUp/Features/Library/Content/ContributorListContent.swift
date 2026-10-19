import SwiftUI
import Shared

/// A single-role contributor Library tab (Authors or Narrators): the observer's sections, sliced by
/// the shared letter runs, with a width-driven layout and a section index on a name sort. The role is supplied
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
    /// The people, sliced by the shared letter runs: one lettered section per run on a name sort,
    /// one unlettered section on any other.
    let sections: [LetterRun.Section<ContributorRow>]
    /// The index's letters: each run's letter once, in list order; empty without a name sort.
    let indexLetters: [String]
    let roleKind: RoleChip.Kind
    /// The section switcher, shown as the first row in every state; `nil` in the iPad sidebar.
    var picker: LibrarySectionPicker?

    @State private var isScrolling = false
    @State private var scrollTarget: String?
    /// Available list width, read non-intrusively to drive the responsive column count. Read via
    /// `onGeometryChange` rather than a greedy `GeometryReader`.
    @State private var listWidth: CGFloat = 0

    /// Lettered sections exist only on a name sort; the shared state leaves the runs empty otherwise.
    private var isLettered: Bool { sections.first.map { !$0.letter.isEmpty } ?? false }
    private var isAuthors: Bool { roleKind == .author }

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
                if !indexLetters.isEmpty {
                    SectionIndexBar(
                        letters: indexLetters,
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
    nonisolated static func scrollTarget(
        forLetter letter: String,
        in sections: [LetterRun.Section<ContributorRow>]
    ) -> String? {
        sections.first { $0.letter == letter }?.items.first?.id
    }

    private var list: some View {
        List {
            // The switcher as the list's first row: clear, edge to edge within the inset column.
            if let picker {
                Section {
                    picker
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }
            }
            ForEach(sections) { group in
                Section {
                    ForEach(group.items) { person in
                        PersonRow(contributor: person, kind: roleKind)
                    }
                } header: {
                    if isLettered { Text(group.letter) }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private func grid(columns: Int) -> some View {
        ScrollView {
            picker?.headerRow(horizontalMargin: Spacing.xxl)
            LazyVGrid(
                columns: Array(repeating: GridItem(.flexible(), spacing: 16, alignment: .top), count: columns),
                alignment: .leading,
                spacing: 12,
                pinnedViews: isLettered ? [.sectionHeaders] : []
            ) {
                ForEach(sections) { group in
                    Section {
                        ForEach(group.items) { person in
                            PersonRow(contributor: person, kind: roleKind, style: .card)
                                .id(person.id)
                        }
                    } header: {
                        if isLettered {
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
        LibrarySectionState(picker: picker) {
            ContentUnavailableView(
                String(localized: "library.contributors_empty"),
                systemImage: isAuthors ? "person.fill" : "waveform.circle.fill",
                description: Text(String(
                    format: String(localized: "library.empty_tab_description"),
                    String(localized: isAuthors ? "library.authors" : "library.narrators")
                ))
            )
        }
        .background(Color.luSurface)
    }
}
