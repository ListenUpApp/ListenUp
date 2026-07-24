import SwiftUI

/// An optional ISO (`yyyy-MM-dd`) date on a single inset card: shows the formatted date
/// or "Not set", a native `DatePicker` to set it, and a Clear affordance. The bound value
/// is the ISO string (`""` when unset).
struct EditDateField: View {
    let label: String
    @Binding var isoDate: String

    private var parsed: Date? { ISODate.parse(isoDate) }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.caption)
                .foregroundStyle(Color.luLabel2)

            HStack {
                if let parsed {
                    DatePicker(
                        "",
                        selection: Binding(
                            get: { parsed },
                            set: { isoDate = ISODate.format($0) }
                        ),
                        displayedComponents: .date
                    )
                    .labelsHidden()
                    Spacer()
                    Button(String(localized: "edit.clear_date")) { isoDate = "" }
                        .font(.subheadline)
                        .foregroundStyle(Color.luTint)
                } else {
                    Text(String(localized: "edit.not_set"))
                        .font(.body)
                        .foregroundStyle(Color.luLabel3)
                    Spacer()
                    Button(String(localized: "edit.set_date")) { isoDate = ISODate.format(Date()) }
                        .font(.subheadline)
                        .foregroundStyle(Color.luTint)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .fieldCard()
    }
}

#Preview("EditDateField") {
    @Previewable @State var born = "1947-09-21"
    @Previewable @State var died = ""
    return VStack(spacing: 16) {
        EditDateField(label: "Born", isoDate: $born)
        EditDateField(label: "Died", isoDate: $died)
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
