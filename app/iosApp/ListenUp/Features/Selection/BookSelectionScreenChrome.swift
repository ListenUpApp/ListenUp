import SwiftUI

/// Screen-level multi-select chrome — a "N selected" title item, a "Done" toolbar button, the bottom
/// action bar (Add to Shelf / admin-only Add to Collection), and the two bulk picker sheets — hosted
/// over a books-bearing screen. Keeps that wiring in one place instead of re-rolling it per screen.
///
/// Entry into selection is a native **long-press a cover → context menu → "Select"**
/// (`SelectableBookCard`); iOS arbitrates the long-press against the card's `NavigationLink` tap, so
/// browsing and selecting never conflict. There is deliberately no idle "Select" button — on
/// Home/Discover it collided with the profile avatar. The toolbar surfaces only once selecting, and
/// the tab bar hides so the action bar owns the bottom.
///
/// `selection` is optional so the screen's `@State` (constructed in `.onAppear`) can be passed
/// straight through; when `nil` the chrome is a no-op.
struct BookSelectionScreenChrome: ViewModifier {
    let selection: BookSelectionObserver?

    func body(content: Content) -> some View {
        if let selection {
            content
                .toolbar { toolbar(selection) }
                // Hide the floating tab bar while selecting so the bottom action bar owns the bottom
                // strip instead of colliding with the tab pills (matches LibraryView).
                .toolbar(selection.isSelecting ? .hidden : .automatic, for: .tabBar)
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
            BookSelectionToolbar(selection: selection)
        }
    }
}

/// The shared selection toolbar — a "N selected" title, a Done exit, and the bottom action bar
/// (Add to Shelf; admin-only Add to Collection). Extracted so the Library grid and the Home/Discover
/// chrome render the *same* toolbar instead of two copies that can silently drift apart.
///
/// The count lives in the `.principal` (title) slot rather than a `.bottomBar` `Text`: a bare `Text`
/// in a bottom bar becomes a Liquid Glass capsule that truncated to "1 sel…" and read as a broken
/// button. Hosts are responsible for collapsing their title to `.inline` while selecting so this
/// principal count doesn't sit under a large title.
struct BookSelectionToolbar: ToolbarContent {
    let selection: BookSelectionObserver

    var body: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            Text(String(format: String(localized: "selection.n_selected"),
                        selection.selectedBookIds.count))
                .font(.headline)
        }
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

            // The Spacer + Add-to-Collection are admin-only; a non-admin sees just Add-to-Shelf, so
            // don't emit a trailing Spacer that would strand the lone button off to one side.
            if selection.isAdmin {
                Spacer()

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

extension View {
    /// Hosts the screen-level multi-select chrome (Done toolbar, bottom action bar, and bulk picker
    /// sheets) bound to a screen-wide `BookSelectionObserver`. A no-op when `selection` is nil.
    func bookSelectionChrome(_ selection: BookSelectionObserver?) -> some View {
        modifier(BookSelectionScreenChrome(selection: selection))
    }
}
