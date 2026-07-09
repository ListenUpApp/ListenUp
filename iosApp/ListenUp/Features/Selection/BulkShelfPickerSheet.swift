import SwiftUI

/// Sheet body for adding the multi-selected books to one of the user's shelves.
///
/// Mirrors `ShelfPickerSheet` but is bound to `BookSelectionObserver` and operates on the whole
/// selection: a leading count line states how many books will be added, each shelf is a plain
/// tappable row (no per-shelf membership checkmark — many books, no single membership), and an
/// inline "New Shelf…" affordance creates a shelf and files every selected book into it in one
/// step. Failures surface on the global `ErrorBus`; on success the VM clears the selection and
/// emits an event the observer reacts to by dismissing this sheet.
struct BulkShelfPickerSheet: View {
    let observer: BookSelectionObserver
    /// Number of books currently selected — shown in the count line.
    let count: Int
    /// Invoked by the Done button; the assembly screen flips the sheet binding.
    let onClose: () -> Void

    @State private var isCreatingShelf = false
    @State private var newShelfName = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(String(format: String(localized: "selection.add_n_to_shelf"), count))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                shelvesSection
                newShelfSection
            }
            .scrollContentBackground(.hidden)
            .navigationTitle(String(localized: "book.detail_add_to_shelf"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(String(localized: "common.done"), action: onClose)
                }
                if observer.isAddingToShelf {
                    ToolbarItem(placement: .topBarLeading) {
                        ProgressView()
                    }
                }
            }
            .presentationDetents([.medium, .large])
            .presentationBackground(.thickMaterial)
        }
    }

    // MARK: - Existing shelves

    private var shelvesSection: some View {
        Section {
            ForEach(observer.myShelves) { shelf in
                Button { observer.addToShelf(shelfId: shelf.id) } label: {
                    Text(shelf.name)
                        .foregroundStyle(.primary)
                }
                .disabled(observer.isAddingToShelf)
                .accessibilityLabel(Text(shelf.name))
            }
        }
    }

    // MARK: - New shelf

    @ViewBuilder
    private var newShelfSection: some View {
        Section {
            if isCreatingShelf {
                TextField(String(localized: "common.shelf_name_hint"), text: $newShelfName)
                    .submitLabel(.done)
                    .onSubmit(createShelf)

                HStack {
                    Button(String(localized: "common.cancel")) {
                        isCreatingShelf = false
                        newShelfName = ""
                    }
                    .foregroundStyle(.secondary)

                    Spacer()

                    Button(String(localized: "common.create"), action: createShelf)
                        .fontWeight(.semibold)
                        .foregroundStyle(Color.listenUpOrange)
                        .disabled(trimmedName.isEmpty || observer.isAddingToShelf)
                }
                // Give each button its own hit target. Without .borderless, two default
                // buttons in a List row make the whole row tappable for BOTH: a tap anywhere
                // outside the tiny "Create" text fires Cancel (clearing the name) then Create,
                // which no-ops on the empty-name guard — a silent create failure.
                .buttonStyle(.borderless)
            } else {
                Button { isCreatingShelf = true } label: {
                    Label(String(localized: "book.detail_new_shelf"), systemImage: "plus")
                        .foregroundStyle(Color.listenUpOrange)
                }
            }
        }
    }

    // MARK: - Actions

    private var trimmedName: String {
        newShelfName.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func createShelf() {
        let name = trimmedName
        guard !name.isEmpty else { return }
        observer.createShelfAndAdd(name: name)
        newShelfName = ""
        isCreatingShelf = false
    }
}
