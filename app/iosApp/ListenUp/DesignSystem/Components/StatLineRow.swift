import SwiftUI

/// A single labelled stat line — a small tonal ``IconTile``, a label, and a trailing
/// tabular-numeric value — for grouped summary cards (import stats, apply progress). Sits flush
/// inside a `.fieldCard()` / `FieldGroup`. Generic and domain-free: the mockup's `StatLine`.
struct StatLineRow: View {
    let systemImage: String
    var tint: Color = .luTint
    let label: String
    let value: String
    /// A muted value reads as secondary (e.g. a "skipped" count that isn't a headline figure).
    var isMuted: Bool = false

    var body: some View {
        HStack(spacing: 13) {
            IconTile(systemImage: systemImage, tint: tint, size: 30)
            Text(label)
                .font(.body)
                .foregroundStyle(.primary)
            Spacer(minLength: 12)
            Text(value)
                .font(.body.weight(.semibold).monospacedDigit())
                .foregroundStyle(isMuted ? Color.luLabel2 : .primary)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(label): \(value)")
    }
}

#Preview("StatLineRow") {
    VStack(spacing: 0) {
        StatLineRow(systemImage: "doc", label: "Records imported", value: "82")
        Divider().padding(.leading, 57)
        StatLineRow(systemImage: "waveform", label: "Sessions imported", value: "959")
        Divider().padding(.leading, 57)
        StatLineRow(systemImage: "xmark", label: "Books skipped", value: "1,904", isMuted: true)
    }
    .fieldCard()
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
