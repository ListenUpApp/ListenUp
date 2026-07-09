import SwiftUI

/// Binds `BookDetailObserver`'s shelves into `NamedEntityPickerSheet` for the single-book "add this
/// book to a shelf" flow: a trailing membership checkmark per shelf (`containsBook`), an always-present
/// create affordance, and an inline shelf-error section. No frost and no count header (detail style).
///
/// The assembly screen presents this via `.sheet`, owning the presentation binding
/// (`observer.showShelfPicker`) and driving `closeShelfPicker()` / `clearShelfError()` on dismiss.
struct ShelfPickerSheet: View {
    let observer: BookDetailObserver
    /// Invoked by the Done button; the assembly screen flips the sheet binding.
    let onClose: () -> Void

    var body: some View {
        NamedEntityPickerSheet(
            title: String(localized: "book.detail_add_to_shelf"),
            rows: observer.myShelves.map { PickerRow(id: $0.id, name: $0.name, isChecked: $0.containsBook) },
            errorText: observer.shelfError,
            isBusy: observer.isAddingToShelf,
            create: InlineCreate(
                label: String(localized: "book.detail_new_shelf"),
                nameHint: String(localized: "common.shelf_name_hint"),
                onCreate: { observer.createShelfAndAdd(name: $0) }
            ),
            onSelect: { observer.addToShelf(shelfId: $0) },
            onClose: onClose
        )
    }
}
