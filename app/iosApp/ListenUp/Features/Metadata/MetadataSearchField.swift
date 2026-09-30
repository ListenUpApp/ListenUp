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
                .foregroundStyle(.secondary)

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
                    .background(Color.luTint, in: .concentric())
            }
            .buttonStyle(PressScaleButtonStyle())
            .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
            .accessibilityLabel(String(localized: "common.search"))
        }
        .padding(.leading, Spacing.m)
        .padding(.trailing, Spacing.xs)
        .frame(height: 50)
        // The submit button's corners follow the field's curve at its inset.
        .containerShape(RoundedRectangle(cornerRadius: Radius.l, style: .continuous))
        .background(
            RoundedRectangle(cornerRadius: Radius.l, style: .continuous)
                .fill(Color.luFill)
                .overlay(
                    RoundedRectangle(cornerRadius: Radius.l, style: .continuous)
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
