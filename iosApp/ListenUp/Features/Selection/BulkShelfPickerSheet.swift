import SwiftUI

/// Binds `BookSelectionObserver`'s shelves into `NamedEntityPickerSheet` for the bulk-selection "add
/// every selected book to a shelf" flow: a count header, frost, an always-present create affordance,
/// and a silent empty state (shelves are always creatable, so no zero-state hint). No per-row
/// checkmark — many books, no single membership. Failures surface on the global `ErrorBus`.
struct BulkShelfPickerSheet: View {
    let observer: BookSelectionObserver
    /// Number of books currently selected — shown in the count line.
    let count: Int
    /// Invoked by the Done button; the assembly screen flips the sheet binding.
    let onClose: () -> Void

    var body: some View {
        NamedEntityPickerSheet(
            title: String(localized: "book.detail_add_to_shelf"),
            rows: observer.myShelves.map { PickerRow(id: $0.id, name: $0.name) },
            headerText: String(format: String(localized: "selection.add_n_to_shelf"), count),
            frosted: true,
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
