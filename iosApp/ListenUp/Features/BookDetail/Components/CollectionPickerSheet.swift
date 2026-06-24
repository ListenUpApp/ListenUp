import SwiftUI

/// Sheet body for filing the current book into one of the admin's collections.
///
/// Lists `observer.allCollections` as a simple tappable list; selecting one calls
/// `addToCollection(collectionId:)`. An in-flight add shows a progress indicator
/// and disables the rows. Mirrors `ShelfPickerSheet`, minus the membership
/// checkmark and inline-create affordance (collections are admin-managed, so a
/// straight pick is all that's needed here).
///
/// The assembly screen presents this via `.sheet`, owning the presentation
/// binding (`observer.showCollectionPicker`) and driving `closeCollectionPicker()`
/// on dismiss. Bound directly to `BookDetailObserver`.
struct CollectionPickerSheet: View {
    let observer: BookDetailObserver
    /// Invoked by the Done button; the assembly screen flips the sheet binding.
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            List {
                collectionsSection
            }
            .navigationTitle(String(localized: "book.detail_collection_picker_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(String(localized: "common.done"), action: onClose)
                }
                if observer.isAddingToCollection {
                    ToolbarItem(placement: .topBarLeading) {
                        ProgressView()
                    }
                }
            }
            .presentationDetents([.medium, .large])
        }
    }

    // MARK: - Collections

    @ViewBuilder
    private var collectionsSection: some View {
        if observer.allCollections.isEmpty {
            ContentUnavailableView(
                String(localized: "book.detail_no_collections"),
                systemImage: "rectangle.stack"
            )
        } else {
            Section {
                ForEach(observer.allCollections) { collection in
                    Button { observer.addToCollection(collectionId: collection.id) } label: {
                        Text(collection.name)
                            .foregroundStyle(.primary)
                    }
                    .disabled(observer.isAddingToCollection)
                    .accessibilityLabel(Text(collection.name))
                }
            }
        }
    }
}
