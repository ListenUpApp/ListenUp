import SwiftUI
import Shared

/// A single-role contributor Library tab (Authors or Narrators): a sorted, letter-grouped
/// list of people, with a responsive single/multi-column layout and an alphabet scrubber on
/// name sort. The role is supplied by the caller so the same view backs both tabs.
struct ContributorListContent: View {
    let contributors: [ContributorRow]
    let sortState: SortState?
    let roleKind: RoleChip.Kind
    let onCategorySelected: (SortCategory) -> Void
    let onDirectionToggle: () -> Void

    @State private var isScrolling = false
    @State private var scrollTarget: String?
    /// Available list width, read non-intrusively (see [listBody]) to drive the responsive
    /// column count. Read via `onGeometryChange` rather than a greedy `GeometryReader`.
    @State private var listWidth: CGFloat = 0

    private let sortCategories: [SortCategory] = [.name, .bookCount]

    private var isNameSort: Bool { sortState?.category == .name }
    private var isAuthors: Bool { roleKind == .author }

    var body: some View {
        if contributors.isEmpty {
            emptyState
        } else {
            listBody
        }
    }

    private var listBody: some View {
        let columns = ContributorColumns.columnCount(availableWidth: listWidth)
        return Group {
            if columns <= 1 {
                singleColumnList
            } else {
                multiColumnList(columns: columns)
            }
        }
        .onGeometryChange(for: CGFloat.self, of: { $0.size.width }, action: { listWidth = $0 })
    }

    private var singleColumnList: some View {
        let groups = isNameSort
            ? ContributorLetterGrouping.group(contributors, key: { $0.name })
            : [ContributorLetterGrouping.Group(letter: "", items: contributors)]
        // Scrubber letters come from the same `groups` the headers render, so the two
        // can never drift, and the list is only grouped once per render.
        let scrubberLetters = isNameSort ? groups.map(\.letter) : []

        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    sortRow.padding(.horizontal)

                    ForEach(groups, id: \.letter) { group in
                        if isNameSort {
                            LetterHeader(letter: group.letter)
                                .id("letter-\(group.letter)")
                                .padding(.horizontal)
                        }
                        FieldGroup(group.items, id: \.id, separatorInset: 78) { person in
                            PersonRow(contributor: person, kind: roleKind)
                        }
                        .padding(.horizontal)
                    }
                }
                .padding(.bottom, 100)
            }
            .scrollContentBackground(.hidden)
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
                        onLetterSelected: { scrollTarget = "letter-\($0)" },
                        isVisible: isScrolling
                    )
                    .padding(.trailing, 8)
                    .padding(.vertical, 60)
                }
            }
            .background(Color.luSurface)
        }
    }

    private func multiColumnList(columns: Int) -> some View {
        let groups = isNameSort
            ? ContributorLetterGrouping.group(contributors, key: { $0.name })
            : [ContributorLetterGrouping.Group(letter: "", items: contributors)]
        let columnGroups: [[ContributorLetterGrouping.Group]] = isNameSort
            ? ContributorColumns.balancedColumns(groups, weight: { $0.items.count }, columns: columns)
            : ContributorColumns.balancedColumns(contributors, weight: { _ in 1 }, columns: columns)
                .map { [ContributorLetterGrouping.Group(letter: "", items: $0)] }

        return ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                sortRow.padding(.horizontal, 36)
                HStack(alignment: .top, spacing: 24) {
                    ForEach(Array(columnGroups.enumerated()), id: \.offset) { _, columnGroup in
                        column(columnGroup)
                    }
                }
                .padding(.horizontal, 36)
            }
            .padding(.bottom, 100)
        }
        .scrollContentBackground(.hidden)
        .background(Color.luSurface)
    }

    private func column(_ groups: [ContributorLetterGrouping.Group]) -> some View {
        LazyVStack(alignment: .leading, spacing: 12) {
            ForEach(groups, id: \.letter) { group in
                if isNameSort {
                    LetterHeader(letter: group.letter)
                }
                FieldGroup(group.items, id: \.id, separatorInset: 78) { person in
                    PersonRow(contributor: person, kind: roleKind)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .top)
    }

    private var sortRow: some View {
        let count = String(
            format: String(localized: isAuthors ? "library.author_count" : "library.narrator_count"),
            contributors.count
        )
        return SortRow(count: count, sortLabel: sortState?.category.label ?? "") {
            ForEach(sortCategories, id: \.rawValue) { cat in
                Button {
                    onCategorySelected(cat)
                } label: {
                    HStack {
                        Text(cat.label)
                        if cat == sortState?.category { Image(systemName: "checkmark") }
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
        }
        .haptic(.selectionTick, trigger: sortState)
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
            .padding(.bottom, 100)
        }
        .scrollContentBackground(.hidden)
        .background(Color.luSurface)
    }
}
