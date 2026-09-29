import SwiftUI

/// An optional ISO (`yyyy-MM-dd`) date as one `Form` row: the label, then the date (a native
/// compact `DatePicker`) with a Clear affordance, or "Not set" with a Set affordance. The bound
/// value is the ISO string (`""` when unset).
struct EditDateField: View {
    let label: String
    @Binding var isoDate: String

    private var parsed: Date? { ISODate.parse(isoDate) }

    var body: some View {
        LabeledContent(label) {
            HStack(spacing: 12) {
                if let parsed {
                    DatePicker(
                        label,
                        selection: Binding(
                            get: { parsed },
                            set: { isoDate = ISODate.format($0) }
                        ),
                        displayedComponents: .date
                    )
                    .labelsHidden()
                    Button(String(localized: "edit.clear_date")) { isoDate = "" }
                        .buttonStyle(.borderless)
                } else {
                    Text(String(localized: "edit.not_set"))
                        .foregroundStyle(Color.luLabel3)
                    Button(String(localized: "edit.set_date")) { isoDate = ISODate.format(Date()) }
                        .buttonStyle(.borderless)
                }
            }
        }
    }
}

#Preview("EditDateField") {
    @Previewable @State var born = "1947-09-21"
    @Previewable @State var died = ""
    return Form {
        EditDateField(label: "Born", isoDate: $born)
        EditDateField(label: "Died", isoDate: $died)
    }
}
