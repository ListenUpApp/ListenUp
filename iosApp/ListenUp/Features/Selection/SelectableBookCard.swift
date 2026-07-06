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
            // Plain value-based NavigationLink (a tap opens the book); selection is entered via a
            // native long-press → context menu → "Select". iOS arbitrates the long-press (menu)
            // against the link's tap, so — unlike the old `.simultaneousGesture` — a long-press
            // release never also navigates. Mirrors the library grid's `bookCell` (BooksContent).
            NavigationLink(value: BookDestination(id: bookId)) { label() }
                .buttonStyle(.pressScaleCard)
                .contextMenu {
                    Button(String(localized: "common.select"), systemImage: "checkmark.circle") {
                        selection.enter(bookId)
                    }
                }
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
