import SwiftUI

/// Sheet body for filing the current book into one of the admin's collections.
///
/// Lists `observer.allCollections` as a simple tappable list; selecting one calls
/// `addToCollection(collectionId:)`. Admins may also create a new collection inline
/// via a "New Collection…" affordance that reveals a text field and creates a
/// collection containing the book in one step. Collection errors surface inline; an
/// in-flight add shows a progress indicator and disables the rows.
///
/// The assembly screen presents this via `.sheet`, owning the presentation
/// binding (`observer.showCollectionPicker`) and driving `closeCollectionPicker()` /
/// `clearCollectionError()` on dismiss. Bound directly to `BookDetailObserver`.
struct CollectionPickerSheet: View {
    let observer: BookDetailObserver
    /// Invoked by the Done button; the assembly screen flips the sheet binding.
    let onClose: () -> Void

    @State private var isCreatingCollection = false
    @State private var newCollectionName = ""

    var body: some View {
        NavigationStack {
            List {
                collectionsSection
                // Collections are admin-managed: the create affordance is admin-only
                // (belt-and-suspenders — the whole picker is already admin-gated).
                if observer.isAdmin {
                    newCollectionSection
                }
                if let collectionError = observer.collectionError {
                    Section {
                        Text(collectionError)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
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
