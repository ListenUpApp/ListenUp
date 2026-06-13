import SwiftUI

/// A labeled text field rendered as a `FieldGroup` row — single-line or multi-line.
struct EditField: View {
    let label: String
    @Binding var text: String
    var axis: Axis = .horizontal
    var placeholder: String = ""

    var body: some View {
        FieldGroup([Row(label: label)], id: \.id) { _ in
            VStack(alignment: .leading, spacing: 4) {
                Text(label)
                    .font(.caption)
                    .foregroundStyle(Color.luLabel2)
                TextField(placeholder, text: $text, axis: axis)
                    .font(.body)
                    .foregroundStyle(.primary)
                    .lineLimit(axis == .vertical ? 3 ... 8 : 1 ... 1)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
        }
    }

    private struct Row: Identifiable { let id = UUID(); let label: String }
}

#Preview("EditField") {
    @Previewable @State var name = "The Stormlight Archive"
    @Previewable @State var desc = ""
    return VStack(spacing: 16) {
        EditField(label: "Name", text: $name)
        EditField(label: "Description", text: $desc, axis: .vertical, placeholder: "Add a description")
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
