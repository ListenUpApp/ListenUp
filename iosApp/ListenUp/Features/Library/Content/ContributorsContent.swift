import SwiftUI
@preconcurrency import Shared
import UIKit

/// The merged Contributors Library tab: a segmented Authors|Narrators control over a
/// sorted, letter-grouped contributor list (with alphabet scrubber on name sort).
struct ContributorsContent: View {
    let authors: [ContributorWithBookCount]
    let narrators: [ContributorWithBookCount]
    let authorsSortState: SortState?
    let narratorsSortState: SortState?
    let onAuthorsCategorySelected: (SortCategory) -> Void
    let onAuthorsDirectionToggle: () -> Void
    let onNarratorsCategorySelected: (SortCategory) -> Void
    let onNarratorsDirectionToggle: () -> Void

    private enum Segment: Hashable { case authors, narrators }

    @State private var segment: Segment = .authors
    @State private var isScrolling = false
    @State private var scrollTarget: String?

    private let sortCategories: [SortCategory] = [.name, .bookCount]

    // Active-segment selectors
    private var list: [ContributorWithBookCount] { segment == .authors ? authors : narrators }
    private var sortState: SortState? { segment == .authors ? authorsSortState : narratorsSortState }
    private var roleKind: RoleChip.Kind { segment == .authors ? .author : .narrator }
    private var isNameSort: Bool { sortState?.category == .name }

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $segment) {
                Text(String(localized: "library.authors")).tag(Segment.authors)
                Text(String(localized: "library.narrators")).tag(Segment.narrators)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.top, 8)
            .sensoryFeedback(.selection, trigger: segment)

            if list.isEmpty {
                emptyState
            } else {
                listBody
            }
        }
    }

    private var listBody: some View {
        GeometryReader { geo in
            let columns = ContributorColumns.columnCount(availableWidth: geo.size.width)
            Group {
                if columns <= 1 {
                    singleColumnList
                } else {
                    multiColumnList(columns: columns)
                }
            }
        }
    }

    private var singleColumnList: some View {
        let groups = isNameSort
            ? ContributorLetterGrouping.group(list, key: { $0.contributor.name })
            : [ContributorLetterGrouping.Group(letter: "", items: list)]
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
                        FieldGroup(group.items, id: \.contributorIdString, separatorInset: 78) { person in
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
            .onChange(of: scrollTarget) { _, newTarget in
                if let target = newTarget {
                    withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo(target, anchor: .top) }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { scrollTarget = nil }
                }
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
            ? ContributorLetterGrouping.group(list, key: { $0.contributor.name })
            : [ContributorLetterGrouping.Group(letter: "", items: list)]
        let columnGroups: [[ContributorLetterGrouping.Group]] = isNameSort
            ? ContributorColumns.balancedColumns(groups, weight: { $0.items.count }, columns: columns)
            : ContributorColumns.balancedColumns(list, weight: { _ in 1 }, columns: columns)
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
                FieldGroup(group.items, id: \.contributorIdString, separatorInset: 78) { person in
                    PersonRow(contributor: person, kind: roleKind)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .top)
    }

    private var sortRow: some View {
        let count = String(
            format: String(localized: segment == .authors ? "library.author_count" : "library.narrator_count"),
            list.count
        )
        let onCategory = segment == .authors ? onAuthorsCategorySelected : onNarratorsCategorySelected
        let onDirection = segment == .authors ? onAuthorsDirectionToggle : onNarratorsDirectionToggle
        return SortRow(count: count, sortLabel: sortState?.category.label ?? "") {
            ForEach(sortCategories, id: \.name) { cat in
                Button {
                    UISelectionFeedbackGenerator().selectionChanged()
                    onCategory(cat)
                } label: {
                    HStack {
                        Text(cat.label)
                        if cat == sortState?.category { Image(systemName: "checkmark") }
                    }
                }
            }
            Divider()
            Button {
                UISelectionFeedbackGenerator().selectionChanged()
                onDirection()
            } label: { Text(String(localized: "common.direction")) }
        }
    }

    private var emptyState: some View {
        ContentUnavailableView(
            String(localized: "library.contributors_empty"),
            systemImage: segment == .authors ? "person.fill" : "waveform.circle.fill",
            description: Text(String(
                format: String(localized: "library.empty_tab_description"),
                String(localized: segment == .authors ? "library.authors" : "library.narrators")
            ))
        )
    }
}

private extension ContributorWithBookCount {
    var contributorIdString: String { contributor.idString }
}
