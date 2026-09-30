import SwiftUI

// MARK: - Edit-form section chrome

// Lifted out of `BookEditView` so the bulk metadata editor renders the same form as the
// single-book one rather than a near-copy of it. Two screens sharing one set is the point:
// a copy drifts the first time either is tuned, and the two forms sit one tap apart.
//
// Only the two pieces a field-based form needs came across. `RemovableChip`, `ChipFlow` and
// `EmptyRelationHint` are relation chrome and stay private to `BookEditView` until the bulk
// editor grows relation pickers and genuinely shares them.

/// The edit-form section shell: a `Form` `Section` under a system header. Kept as one name so the
/// single-book and bulk editors can't drift apart in how they head a group of fields.
struct EditSection<Content: View>: View {
    let title: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        Section(title) {
            content()
        }
    }
}

/// A caption-labelled `Form` row wrapping a non-text control (picker, date row), so it reads the
/// same as a labelled `AppTextField`: caption above, control below.
struct LabeledFieldRow<Content: View>: View {
    let label: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
