import SwiftUI

/// A settings row that pairs a leading ``IconTile`` and a title / optional subtitle with a
/// trailing SwiftUI `Toggle`. The clean-coral form of the mockup's toggling `SRow`.
///
/// While `isBusy` is true the toggle is *replaced* by a spinner (not merely disabled) — the
/// load-bearing guard against a second flip landing before the in-flight write resolves.
/// The row supplies its own horizontal/vertical padding so it sits flush inside a
/// `.fieldCard()` or a `FieldGroup` row slot.
struct ToggleRow: View {
    let systemImage: String
    var tint: Color = .luTint
    let title: String
    var subtitle: String?
    @Binding var isOn: Bool
    var isBusy: Bool = false

    var body: some View {
        HStack(spacing: 13) {
            IconTile(systemImage: systemImage, tint: tint)
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(.primary)
                if let subtitle {
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel2)
                }
            }
            Spacer(minLength: 12)
            if isBusy {
                ProgressView()
            } else {
                Toggle("", isOn: $isOn)
                    .labelsHidden()
                    .tint(.luTint)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .accessibilityElement(children: .combine)
    }
}

#Preview("ToggleRow") {
    @Previewable @State var open = false
    return VStack(spacing: 16) {
        ToggleRow(
            systemImage: "person.badge.plus",
            tint: .green,
            title: "Open registration",
            subtitle: "Allow anyone to request an account",
            isOn: $open
        )
        .fieldCard()

        ToggleRow(
            systemImage: "person.badge.plus",
            tint: .green,
            title: "Saving…",
            subtitle: "In flight",
            isOn: $open,
            isBusy: true
        )
        .fieldCard()
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
