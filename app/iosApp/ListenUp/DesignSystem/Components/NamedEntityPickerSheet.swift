import SwiftUI

/// A native value row for `NamedEntityPickerSheet`. Never a Swift-Export-bridged Kotlin object
/// (iosApp rule 8) — each wrapper maps its observer rows into this at the boundary.
struct PickerRow: Identifiable, Equatable {
    let id: String
    let name: String
    /// Trailing membership checkmark (the book-detail shelf sheet); `false` everywhere else.
    var isChecked: Bool = false
}

/// How the picker renders when there are no rows.
enum PickerEmptyState {
    /// Just an empty list — the create affordance is the only content (shelves).
    case silent
    /// A footnote hint row above the create affordance (bulk collection).
    case hint(String)
    /// A full `ContentUnavailableView` replacing the list (book-detail collection).
    case unavailable(String, systemImage: String)
}

/// The inline "New …" create affordance. `nil` hides the create section entirely
/// (e.g. a non-admin viewing collections).
struct InlineCreate {
    let label: String
    let nameHint: String
    let onCreate: (String) -> Void
}

/// One sheet for the whole "pick a named entity, or make a new one and add to it" interaction —
/// the shape the bulk/detail collection and shelf picker sheets all shared. Callers pass their
/// localized strings and bind the entity-specific observer calls into the closures; nothing here
/// knows whether it's showing collections or shelves.
struct NamedEntityPickerSheet: View {
    let title: String
    let rows: [PickerRow]
    var headerText: String?
    var emptyState: PickerEmptyState = .silent
    var errorText: String?
    var frosted: Bool = false
    let isBusy: Bool
    var create: InlineCreate?
    let onSelect: (String) -> Void
    let onClose: () -> Void

    @State private var isCreating = false
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            List {
                if let headerText {
                    Section {
                        Text(headerText)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                rowsSection

                if let create {
                    createSection(create)
                }

                if let errorText {
                    Section {
                        Text(errorText)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
            }
            .scrollContentBackground(frosted ? .hidden : .automatic)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(String(localized: "common.done"), action: onClose)
                }
                if isBusy {
                    ToolbarItem(placement: .topBarLeading) {
                        ProgressView()
                    }
                }
            }
            .presentationDetents([.medium, .large])
        }
        .presentationBackgroundFrosted(frosted)
    }

    // MARK: - Rows

    @ViewBuilder
    private var rowsSection: some View {
        switch emptyState {
        case .unavailable(let text, let icon) where rows.isEmpty:
            ContentUnavailableView(text, systemImage: icon)
        case .hint(let text) where rows.isEmpty:
            Section {
                Text(text)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        default:
            Section {
                ForEach(rows) { row in
                    rowButton(row)
                }
            }
        }
    }

    @ViewBuilder
    private func rowButton(_ row: PickerRow) -> some View {
        Button { onSelect(row.id) } label: {
            HStack {
                Text(row.name)
                    .foregroundStyle(.primary)
                if row.isChecked {
                    Spacer()
                    Image(systemName: "checkmark")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.listenUpOrange)
                }
            }
        }
        .disabled(isBusy)
        .accessibilityLabel(Text(row.name))
        .accessibilityValue(row.isChecked ? Text(String(localized: "common.selected")) : Text(""))
    }

    // MARK: - Create

    @ViewBuilder
    private func createSection(_ create: InlineCreate) -> some View {
        Section {
            if isCreating {
                TextField(create.nameHint, text: $newName)
                    .submitLabel(.done)
                    .onSubmit { submit(create) }

                HStack {
                    Button(String(localized: "common.cancel")) {
                        isCreating = false
                        newName = ""
                    }
                    .foregroundStyle(.secondary)

                    Spacer()

                    Button(String(localized: "common.create")) { submit(create) }
                        .fontWeight(.semibold)
                        .foregroundStyle(Color.listenUpOrange)
                        .disabled(trimmedName.isEmpty || isBusy)
                }
                // .borderless gives each button its own hit target — without it a tap
                // anywhere in the row fires Cancel then Create, a silent create failure.
                .buttonStyle(.borderless)
            } else {
                Button { isCreating = true } label: {
                    Label(create.label, systemImage: "plus")
                        .foregroundStyle(Color.listenUpOrange)
                }
            }
        }
    }

    private var trimmedName: String {
        newName.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func submit(_ create: InlineCreate) {
        let name = trimmedName
        guard !name.isEmpty else { return }
        create.onCreate(name)
        newName = ""
        isCreating = false
    }
}

private extension View {
    /// The bulk sheets float on a `.thickMaterial` sheet background; the detail sheets use the
    /// default. Applied conditionally so the two treatments stay identical to the originals.
    @ViewBuilder
    func presentationBackgroundFrosted(_ frosted: Bool) -> some View {
        if frosted {
            presentationBackground(.thickMaterial)
        } else {
            self
        }
    }
}
