import SwiftUI

/// A numbered "how it works" step row — a tonal ``IconTile``, a title / subtitle, and a trailing
/// step-number chip. For onboarding / explainer lists that walk a flow's stages. Sits flush
/// inside a `.fieldCard()` / `FieldGroup`. Generic and domain-free: the mockup's `StepRow`.
struct NumberedStepRow: View {
    let number: Int
    let systemImage: String
    var tint: Color = .luTint
    let title: String
    var subtitle: String?

    var body: some View {
        HStack(spacing: 13) {
            IconTile(systemImage: systemImage, tint: tint, size: 30)
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(.primary)
                if let subtitle {
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel2)
                        .multilineTextAlignment(.leading)
                }
            }
            Spacer(minLength: 12)
            Text("\(number)")
                .font(.footnote.weight(.bold))
                .foregroundStyle(Color.luLabel2)
                .frame(width: 24, height: 24)
                .background(Color.luFill, in: Circle())
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(String(format: String(localized: "common.step_n"), number)). \(title)")
    }
}

#Preview("NumberedStepRow") {
    VStack(spacing: 0) {
        NumberedStepRow(number: 1, systemImage: "doc", title: "Choose a backup", subtitle: "Pick an export file")
        Divider().padding(.leading, 57)
        NumberedStepRow(number: 2, systemImage: "person", title: "Match users", subtitle: "Map ABS to ListenUp")
        Divider().padding(.leading, 57)
        NumberedStepRow(number: 3, systemImage: "waveform", title: "Apply import", subtitle: "Write to your history")
    }
    .fieldCard()
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
