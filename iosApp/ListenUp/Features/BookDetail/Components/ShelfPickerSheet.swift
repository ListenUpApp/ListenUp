import SwiftUI

/// Sheet body for adding the current book to one of the user's shelves.
///
/// Lists `observer.myShelves`, marking membership with a trailing checkmark, and
/// offers an inline "New Shelf…" affordance that reveals a text field and creates
/// a shelf containing the book in one step. Shelf errors surface inline; an
/// in-flight add shows a progress indicator.
///
/// The assembly screen presents this via `.sheet`, owning the presentation
/// binding (`observer.showShelfPicker`) and driving `closeShelfPicker()` /
/// `clearShelfError()` on dismiss. Bound directly to `BookDetailObserver`.
struct ShelfPickerSheet: View {
    let observer: BookDetailObserver
    /// Invoked by the Done button; the assembly screen flips the sheet binding.
    let onClose: () -> Void

    @State private var isCreatingShelf = false
    @State private var newShelfName = ""

    var body: some View {
        NavigationStack {
            List {
                shelvesSection
                newShelfSection
                if let shelfError = observer.shelfError {
                    Section {
                        Text(shelfError)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
            }
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
        }
    }

    // MARK: - Existing shelves

    private var shelvesSection: some View {
        Section {
            ForEach(observer.myShelves) { shelf in
                Button { observer.addToShelf(shelfId: shelf.id) } label: {
                    HStack {
                        Text(shelf.name)
                            .foregroundStyle(.primary)
                        Spacer()
                        if shelf.containsBook {
                            Image(systemName: "checkmark")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Color.listenUpOrange)
                        }
                    }
                }
                .disabled(observer.isAddingToShelf)
                .accessibilityLabel(Text(shelf.name))
                .accessibilityValue(shelf.containsBook
                    ? Text(String(localized: "common.selected"))
                    : Text(""))
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
