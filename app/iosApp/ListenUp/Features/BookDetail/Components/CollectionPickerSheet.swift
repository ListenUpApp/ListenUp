import SwiftUI

/// Binds `BookDetailObserver`'s collections into `NamedEntityPickerSheet` for the single-book "file
/// this book into a collection" flow: a `ContentUnavailableView` empty state, an admin-gated create
/// affordance, and an inline collection-error section. No frost and no count header (detail style).
///
/// The assembly screen presents this via `.sheet`, owning the presentation binding
/// (`observer.showCollectionPicker`) and driving `closeCollectionPicker()` / `clearCollectionError()`
/// on dismiss.
struct CollectionPickerSheet: View {
    let observer: BookDetailObserver
    /// Invoked by the Done button; the assembly screen flips the sheet binding.
    let onClose: () -> Void

    var body: some View {
        NamedEntityPickerSheet(
            title: String(localized: "book.detail_collection_picker_title"),
            rows: observer.allCollections.map { PickerRow(id: $0.id, name: $0.name) },
            emptyState: .unavailable(
                String(localized: "book.detail_no_collections"),
                systemImage: "rectangle.stack"
            ),
            errorText: observer.collectionError,
            isBusy: observer.isAddingToCollection,
            // Collections are admin-managed: the create affordance is admin-only (belt-and-suspenders
            // — the whole picker is already admin-gated).
            create: observer.isAdmin
                ? InlineCreate(
                    label: String(localized: "book.detail_new_collection"),
                    nameHint: String(localized: "common.collection_name_hint"),
                    onCreate: { observer.createCollectionAndAdd(name: $0) }
                  )
                : nil,
            onSelect: { observer.addToCollection(collectionId: $0) },
            onClose: onClose
        )
    }
}
