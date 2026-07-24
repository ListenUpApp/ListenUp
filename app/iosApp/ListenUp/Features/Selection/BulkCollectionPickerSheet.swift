import SwiftUI

/// Binds `BookSelectionObserver`'s collections into `NamedEntityPickerSheet` for the bulk-selection
/// "file every selected book into a collection" flow: a count header, frost, an always-present
/// create affordance, and a hint-row empty state (an admin with zero collections still needs to make
/// the first one). Failures surface on the global `ErrorBus`; on success the VM clears the selection
/// and the observer dismisses this sheet.
struct BulkCollectionPickerSheet: View {
    let observer: BookSelectionObserver
    /// Number of books currently selected — shown in the count line.
    let count: Int
    /// Invoked by the Done button; the assembly screen flips the sheet binding.
    let onClose: () -> Void

    var body: some View {
        NamedEntityPickerSheet(
            title: String(localized: "book.detail_collection_picker_title"),
            rows: observer.allCollections.map { PickerRow(id: $0.id, name: $0.name) },
            headerText: String(format: String(localized: "selection.add_n_to_collection"), count),
            emptyState: .hint(String(localized: "book.detail_no_collections")),
            frosted: true,
            isBusy: observer.isAddingToCollection,
            create: InlineCreate(
                label: String(localized: "book.detail_new_collection"),
                nameHint: String(localized: "common.collection_name_hint"),
                onCreate: { observer.createCollectionAndAdd(name: $0) }
            ),
            onSelect: { observer.addToCollection(collectionId: $0) },
            onClose: onClose
        )
    }
}
