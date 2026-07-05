import SwiftUI

/// Shared multi-select chrome for a book carousel card, mirroring the library grid's `bookCell`
/// (`BooksContent`) so Home + Discover behave identically: the cover shows a selection circle while
/// selecting, a tap toggles selection instead of navigating, and a long-press is the secondary entry
/// into selection mode.
///
/// Carousel cards each own a `BookCoverImage` + `NavigationLink`, so rather than re-roll the overlay
/// and tap-switch in every card, a card composes:
///   1. `.bookSelectionCircle(...)` on its cover image, and
///   2. `SelectableBookCard { card }` as its root, which chooses Button(toggle) vs.
///      NavigationLink(navigate)+long-press based on the screen-wide `BookSelectionObserver`.
///
/// `selection` is optional: when `nil` the card renders a plain `NavigationLink` and the chrome is a
/// no-op, so callers that don't participate in multi-select stay unchanged.
struct SelectableBookCard<Label: View>: View {
    let bookId: String
    let selection: BookSelectionObserver?
    @ViewBuilder let label: () -> Label

    var body: some View {
        if let selection, selection.isSelecting {
            Button { selection.toggle(bookId) } label: { label() }
                .buttonStyle(.pressScaleCard)
        } else if let selection {
            NavigationLink(value: BookDestination(id: bookId)) { label() }
                .buttonStyle(.pressScaleCard)
                // A long-press attached directly to a NavigationLink is swallowed by the link's
                // own press recognizer and never fires. `simultaneousGesture` lets the long-press
                // run alongside the tap: a quick tap still navigates, a hold enters selection.
                .simultaneousGesture(
                    LongPressGesture(minimumDuration: 0.4)
                        .onEnded { _ in selection.enter(bookId) }
                )
        } else {
            NavigationLink(value: BookDestination(id: bookId)) { label() }
                .buttonStyle(.pressScaleCard)
        }
    }
}

extension View {
    /// Overlays the multi-select selection circle (top-leading, matching `BookCoverCard`) when the
    /// screen is selecting. A no-op when `selection` is nil or not in selection mode.
    func bookSelectionCircle(bookId: String, selection: BookSelectionObserver?) -> some View {
        overlay(alignment: .topLeading) {
            if let selection, selection.isSelecting {
                let isSelected = selection.isSelected(bookId)
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22))
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, isSelected ? Color.listenUpOrange : Color.black.opacity(0.35))
                    .padding(6)
                    .accessibilityLabel(Text(isSelected
                        ? String(localized: "common.selected")
                        : String(localized: "common.not_selected")))
            }
        }
    }
}
