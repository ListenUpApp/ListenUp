import SwiftUI
@preconcurrency import Shared
import UIKit

/// Content view for the Narrators tab in the Library.
///
/// Features:
/// - List of ContributorRow components (same as Authors, different role)
/// - Floating sort button (Name, Book Count)
/// - Alphabet scrubber when sorted by name
/// - Empty state when no narrators
struct NarratorsContent: View {
    let narrators: [ContributorWithBookCount_]
    let sortState: SortState?
    let onCategorySelected: (SortCategory) -> Void
    let onDirectionToggle: () -> Void

    @State private var isScrolling = false
    @State private var scrollTarget: String?

    /// Available sort categories for contributors
    private let sortCategories: [SortCategory] = [.name, .bookCount]

    var body: some View {
        if narrators.isEmpty {
            emptyState
        } else {
            narratorsList
        }
    }

    // MARK: - Narrators List

    private var narratorsList: some View {
        let letters = buildAlphabetIndex()

        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 12) {
                    // Spacer for sort button
                    Color.clear.frame(height: 32)

                    ForEach(Array(narrators.enumerated()), id: \.offset) { _, narrator in
                        let narratorId = String(describing: narrator.contributor.id)
                        ContributorRow(contributor: narrator, role: .narrator)
                            .id("narrator-\(narratorId)")
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

    /// Builds alphabet index mapping letters to first narrator ID for that letter.
    private func buildAlphabetIndex() -> [(letter: String, firstId: String)] {
        guard sortState?.category == .name else { return [] }

        var index: [(letter: String, firstId: String)] = []
        var seenLetters: Set<String> = []

        for narrator in narrators {
            let name = narrator.contributor.name
            guard let firstChar = name.first else { continue }
            let letter = firstChar.isLetter ? String(firstChar).uppercased() : "#"

            if !seenLetters.contains(letter) {
                seenLetters.insert(letter)
                let narratorId = String(describing: narrator.contributor.id)
                index.append((letter: letter, firstId: "narrator-\(narratorId)"))
            }
        }

        return index
    }

    // MARK: - Empty State

    private var emptyState: some View {
        VStack(spacing: 16) {
            Spacer()

            Image(systemName: "waveform.circle.fill")
                .font(.system(size: 64))
                .foregroundStyle(.secondary)

            Text(String(format: String(localized: "common.no_items_yet"), "narrators"))
                .font(.title2.bold())

            Text(String(format: String(localized: "library.empty_tab_description"), "Narrators"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            Spacer()
        }
        .frame(maxWidth: .infinity)
    }
}
