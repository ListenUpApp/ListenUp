import SwiftUI

// MARK: - Edit-form section chrome

// Lifted out of `BookEditView` so the bulk metadata editor renders the same form as the
// single-book one rather than a near-copy of it. Two screens sharing one set is the point:
// a copy drifts the first time either is tuned, and the two forms sit one tap apart.
//
// Only the two pieces a field-based form needs came across. `RemovableChip`, `ChipFlow` and
// `EmptyRelationHint` are relation chrome and stay private to `BookEditView` until the bulk
// editor grows relation pickers and genuinely shares them.

/// Uppercased caption header + grouped card, the edit-form section shell.
struct EditSection<Content: View>: View {
    let title: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.caption)
                .fontWeight(.semibold)
                .foregroundStyle(Color.luLabel2)
                .textCase(.uppercase)
                .padding(.leading, 4)
            VStack(alignment: .leading, spacing: 12) {
                content()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .fieldCard()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A caption-labelled field card wrapping a non-text control (picker, date row) so it reads the
/// same as an `AppTextField().fieldCard()`: caption above, control below, same insets and surface.
struct LabeledFieldRow<Content: View>: View {
    let label: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.caption)
                .foregroundStyle(Color.luLabel2)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .fieldCard()
    }
}
