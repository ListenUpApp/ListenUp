import SwiftUI

/// Screen-level multi-select chrome — the native Select/Done toolbar button, the bottom action bar
/// (Add to Shelf / count / admin-only Add to Collection), and the two bulk picker sheets — hosted
/// over a books-bearing screen. Mirrors `LibraryView`'s inline structure (Task 6) so Home + Discover
/// behave identically, and keeps that wiring in one place instead of re-rolling it per screen.
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
        ToolbarItem(placement: .topBarTrailing) {
            Button(selection.isSelecting
                ? String(localized: "common.done")
                : String(localized: "common.select")) {
                if selection.isSelecting {
                    selection.exit()
                } else {
                    selection.startSelecting()
                }
            }
        }
        if selection.isSelecting {
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
