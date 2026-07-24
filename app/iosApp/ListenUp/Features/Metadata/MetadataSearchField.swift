import SwiftUI

/// The metadata search field: a rounded fill row with a leading magnifier, a monospaced query
/// entry (ASINs read better mono), and a trailing coral submit button. Submitting via the keyboard
/// or the button both fire `onSubmit`.
struct MetadataSearchField: View {
    @Binding var text: String
    let onSubmit: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.body.weight(.medium))
                .foregroundStyle(Color.luLabel2)

            TextField(String(localized: "common.search"), text: $text)
                .font(.callout.monospaced())
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .onSubmit(onSubmit)
                .accessibilityIdentifier("metadata_search_field")

            Button(action: onSubmit) {
                Image(systemName: "arrow.right")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Color.luOnTint)
                    .frame(width: 38, height: 38)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.luTint))
            }
            .buttonStyle(PressScaleButtonStyle())
            .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
            .accessibilityLabel(String(localized: "common.search"))
        }
        .padding(.leading, 16)
        .padding(.trailing, 6)
        .frame(height: 50)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.luFill)
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(Color.luSeparator, lineWidth: 0.5)
                )
        )
    }
}

#Preview("MetadataSearchField") {
    struct Demo: View {
        @State private var text = "B0D6H2N1YL"
        var body: some View {
            MetadataSearchField(text: $text, onSubmit: {})
                .padding()
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .background(Color.luSurface)
        }
    }
    return Demo()
}
