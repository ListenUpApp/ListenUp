import SwiftUI

/// Screen-level multi-select chrome — a "Done" toolbar button, the bottom action bar
/// (Add to Shelf / count / admin-only Add to Collection), and the two bulk picker sheets — hosted
/// over a books-bearing screen. Keeps that wiring in one place instead of re-rolling it per screen.
///
/// Entry into selection is **long-press a cover** (`SelectableBookCard.onLongPressGesture`); there
/// is deliberately no idle "Select" button here — on Home/Discover it collided with the profile
/// avatar in the top bar. The toolbar surfaces only once selecting, as a "Done" exit. (The Library
/// grid keeps its own explicit Select button — it's the natural bulk surface.)
///
/// `selection` is optional so the screen's `@State` (constructed in `.onAppear`) can be passed
/// straight through; when `nil` the chrome is a no-op.
struct BookSelectionScreenChrome: ViewModifier {
    let selection: BookSelectionObserver?

    func body(content: Content) -> some View {
        if let selection {
            content
                .toolbar { toolbar(selection) }
                .sheet(isPresented: Binding(
                    get: { selection.showShelfPicker },
                    set: { selection.showShelfPicker = $0 }
                )) {
                    BulkShelfPickerSheet(
                        observer: selection,
                        count: selection.selectedBookIds.count
                    ) { selection.showShelfPicker = false }
                }
                .sheet(isPresented: Binding(
                    get: { selection.isAdmin && selection.showCollectionPicker },
                    set: { selection.showCollectionPicker = $0 }
                )) {
                    BulkCollectionPickerSheet(
                        observer: selection,
                        count: selection.selectedBookIds.count
                    ) { selection.showCollectionPicker = false }
                }
        } else {
            content
        }
    }

    @ToolbarContentBuilder
    private func toolbar(_ selection: BookSelectionObserver) -> some ToolbarContent {
        if selection.isSelecting {
            ToolbarItem(placement: .topBarTrailing) {
                Button(String(localized: "common.done")) { selection.exit() }
            }
            ToolbarItemGroup(placement: .bottomBar) {
                Button {
                    selection.showShelfPicker = true
                } label: {
                    Label(String(localized: "book.detail_add_to_shelf"),
                          systemImage: "rectangle.stack.badge.plus")
                }
                .disabled(selection.selectedBookIds.isEmpty)

                Spacer()

                Text(String(format: String(localized: "selection.n_selected"),
                            selection.selectedBookIds.count))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                Spacer()

                if selection.isAdmin {
                    Button {
                        selection.showCollectionPicker = true
                    } label: {
                        Label(String(localized: "book.detail_add_to_collection"),
                              systemImage: "folder.badge.plus")
                    }
                    .disabled(selection.selectedBookIds.isEmpty)
                }
            }
        }
    }
}

extension View {
    /// Hosts the screen-level multi-select chrome (Select/Done toolbar, bottom action bar, and bulk
    /// picker sheets) bound to a screen-wide `BookSelectionObserver`. A no-op when `selection` is nil.
    func bookSelectionChrome(_ selection: BookSelectionObserver?) -> some View {
        modifier(BookSelectionScreenChrome(selection: selection))
    }
}
