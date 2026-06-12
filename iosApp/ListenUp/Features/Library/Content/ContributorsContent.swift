import SwiftUI
@preconcurrency import Shared
import UIKit

/// The merged Contributors Library tab: a segmented Authors|Narrators control over a
/// sorted, letter-grouped contributor list (with alphabet scrubber on name sort).
struct ContributorsContent: View {
    let authors: [ContributorWithBookCount_]
    let narrators: [ContributorWithBookCount_]
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
    private var list: [ContributorWithBookCount_] { segment == .authors ? authors : narrators }
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
        let groups = isNameSort
            ? ContributorLetterGrouping.group(list, key: { $0.contributor.name })
            : [ContributorLetterGrouping.Group(letter: "", items: list)]

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
                if isNameSort {
                    let letters = ContributorLetterGrouping.group(list, key: { $0.contributor.name }).map(\.letter)
                    if !letters.isEmpty {
                        SectionIndexBar(
                            letters: letters,
                            onLetterSelected: { scrollTarget = "letter-\($0)" },
                            isVisible: isScrolling
                        )
                        .padding(.trailing, 8)
                        .padding(.vertical, 60)
                    }
                }
            }
            .background(Color.luSurface)
        }
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
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: segment == .authors ? "person.fill" : "waveform.circle.fill")
                .font(.system(size: 64))
                .foregroundStyle(.secondary)
            Text(String(localized: "library.contributors_empty"))
                .font(.title2.bold())
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private extension ContributorWithBookCount_ {
    var contributorIdString: String { String(describing: contributor.id) }
}
