import SwiftUI

/// Sheet body for filing the multi-selected books into one of the admin's collections.
///
/// Mirrors `BulkShelfPickerSheet` but is bound to `BookSelectionObserver` and operates on the whole
/// selection: a leading count line states how many books will be added, each collection is a plain
/// tappable row, and an inline "New Collection…" affordance creates a collection and files every
/// selected book into it in one step. The create affordance is always present — an admin with zero
/// collections still needs a way to make the first one — so the empty state is a hint row above it,
/// not a full-screen `ContentUnavailableView`. Admin-gated (the whole sheet is only presented for
/// admins). Failures surface on the global `ErrorBus`; on success the VM clears the selection and
/// emits an event the observer reacts to by dismissing this sheet.
struct BulkCollectionPickerSheet: View {
    let observer: BookSelectionObserver
    /// Number of books currently selected — shown in the count line.
    let count: Int
    /// Invoked by the Done button; the assembly screen flips the sheet binding.
    let onClose: () -> Void

    @State private var isCreatingCollection = false
    @State private var newCollectionName = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(String(format: String(localized: "selection.add_n_to_collection"), count))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                collectionsSection
                newCollectionSection
            }
            .scrollContentBackground(.hidden)
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
            .presentationBackground(.thickMaterial)
        }
    }

    // MARK: - Existing collections

    @ViewBuilder
    private var collectionsSection: some View {
        if observer.allCollections.isEmpty {
            Section {
                Text(String(localized: "book.detail_no_collections"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
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

    // MARK: - New collection

    @ViewBuilder
    private var newCollectionSection: some View {
        Section {
            if isCreatingCollection {
                TextField(String(localized: "common.collection_name_hint"), text: $newCollectionName)
                    .submitLabel(.done)
                    .onSubmit(createCollection)

                HStack {
                    Button(String(localized: "common.cancel")) {
                        isCreatingCollection = false
                        newCollectionName = ""
                    }
                    .foregroundStyle(.secondary)

                    Spacer()

                    Button(String(localized: "common.create"), action: createCollection)
                        .fontWeight(.semibold)
                        .foregroundStyle(Color.listenUpOrange)
                        .disabled(trimmedName.isEmpty || observer.isAddingToCollection)
                }
                // Give each button its own hit target. Without .borderless, two default
                // buttons in a List row make the whole row tappable for BOTH: a tap anywhere
                // outside the tiny "Create" text fires Cancel (clearing the name) then Create,
                // which no-ops on the empty-name guard — a silent create failure.
                .buttonStyle(.borderless)
            } else {
                Button { isCreatingCollection = true } label: {
                    Label(String(localized: "book.detail_new_collection"), systemImage: "plus")
                        .foregroundStyle(Color.listenUpOrange)
                }
            }
        }
    }

    // MARK: - Actions

    private var trimmedName: String {
        newCollectionName.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func createCollection() {
        let name = trimmedName
        guard !name.isEmpty else { return }
        observer.createCollectionAndAdd(name: name)
        newCollectionName = ""
        isCreatingCollection = false
    }
}
