import SwiftUI
@preconcurrency import Shared
import UIKit

/// The merged Contributors Library tab: a segmented Authors|Narrators control over a
/// sorted, letter-grouped contributor list (with alphabet scrubber on name sort).
struct ContributorsContent: View {
    let authors: [ContributorRow]
    let narrators: [ContributorRow]
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
    /// Available list width, read non-intrusively (see [listBody]) to drive the responsive
    /// column count. Read via `onGeometryChange` rather than a greedy `GeometryReader`, which
    /// as a `VStack` sibling starved the Authors/Narrators `Picker` of height (it vanished).
    @State private var listWidth: CGFloat = 0

    private let sortCategories: [SortCategory] = [.name, .bookCount]

    // Active-segment selectors
    private var list: [ContributorRow] { segment == .authors ? authors : narrators }
    private var sortState: SortState? { segment == .authors ? authorsSortState : narratorsSortState }
    private var roleKind: RoleChip.Kind { segment == .authors ? .author : .narrator }
    private var isNameSort: Bool { sortState?.category == .name }

    var body: some View {
        // The Authors|Narrators picker and the contributor count live *inside* the scroll
        // content (not in a wrapping `VStack`) so the `.safeAreaInset(edge: .top)` glass
        // `LibraryChipRow` in `LibraryView` insets them below the header — the same shape
        // Books/Series use. A `VStack` root let the glass row overlay the picker and crowd
        // the count under the header (the bug this fixes).
        if list.isEmpty {
            emptyState
        } else {
            listBody
        }
    }

    /// The segmented Authors|Narrators switcher, rendered as the first scrolling element so it
    /// always sits below the glass header and stays reachable even when a segment is empty.
    private var segmentPicker: some View {
        Picker("", selection: $segment) {
            Text(String(localized: "library.authors")).tag(Segment.authors)
            Text(String(localized: "library.narrators")).tag(Segment.narrators)
        }
        .pickerStyle(.segmented)
        .padding(.top, 8)
        .sensoryFeedback(.selection, trigger: segment)
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
            ? ContributorLetterGrouping.group(list, key: { $0.name })
            : [ContributorLetterGrouping.Group(letter: "", items: list)]
        // Scrubber letters come from the same `groups` the headers render, so the two
        // can never drift, and the list is only grouped once per render.
        let scrubberLetters = isNameSort ? groups.map(\.letter) : []

        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    segmentPicker.padding(.horizontal)

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
            .onChange(of: scrollTarget) { _, newTarget in
                if let target = newTarget {
                    // Instant jump, NOT animated: animating a scrollTo across a large lazy list
                    // forces SwiftUI to lay out the whole intervening range to run the animation,
                    // freezing the main thread on every scrubber letter-change. Native section
                    // indexes jump instantly (#alphabet-scrubber-hang).
                    proxy.scrollTo(target, anchor: .top)
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
            ? ContributorLetterGrouping.group(list, key: { $0.name })
            : [ContributorLetterGrouping.Group(letter: "", items: list)]
        let columnGroups: [[ContributorLetterGrouping.Group]] = isNameSort
            ? ContributorColumns.balancedColumns(groups, weight: { $0.items.count }, columns: columns)
            : ContributorColumns.balancedColumns(list, weight: { _ in 1 }, columns: columns)
                .map { [ContributorLetterGrouping.Group(letter: "", items: $0)] }

        return ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                segmentPicker.padding(.horizontal, 36)

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
        // Keep the segment switcher above the empty placeholder so the user can always switch
        // back to the populated segment — and put it inside a ScrollView so the glass header
        // insets it, exactly like the populated lists.
        ScrollView {
            VStack(spacing: 24) {
                segmentPicker.padding(.horizontal)

                ContentUnavailableView(
                    String(localized: "library.contributors_empty"),
                    systemImage: segment == .authors ? "person.fill" : "waveform.circle.fill",
                    description: Text(String(
                        format: String(localized: "library.empty_tab_description"),
                        String(localized: segment == .authors ? "library.authors" : "library.narrators")
                    ))
                )
                .frame(maxWidth: .infinity, minHeight: 360)
            }
            .padding(.bottom, 100)
        }
        .scrollContentBackground(.hidden)
        .background(Color.luSurface)
    }
}
