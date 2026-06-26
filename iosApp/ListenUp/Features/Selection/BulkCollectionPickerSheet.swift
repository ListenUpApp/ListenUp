import SwiftUI

/// Sheet body for filing the multi-selected books into one of the admin's collections.
///
/// Mirrors `CollectionPickerSheet` but is bound to `BookSelectionObserver` and operates on the
/// whole selection: a leading count line states how many books will be added, and each collection
/// is a plain tappable row. There is no inline "New Collection…" affordance here — the shared VM
/// exposes no bulk create-collection action, matching the Compose multi-select picker
/// (`canCreate = false`); this sheet is additive-to-existing only. Admin-gated (the whole sheet is
/// only presented for admins). Failures surface on the global `ErrorBus`; on success the VM clears
/// the selection and emits an event the observer reacts to by dismissing this sheet.
struct BulkCollectionPickerSheet: View {
    let observer: BookSelectionObserver
    /// Number of books currently selected — shown in the count line.
    let count: Int
    /// Invoked by the Done button; the assembly screen flips the sheet binding.
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(String(format: String(localized: "selection.add_n_to_collection"), count))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
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
