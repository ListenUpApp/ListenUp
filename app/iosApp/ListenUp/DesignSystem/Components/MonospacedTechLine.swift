import SwiftUI

/// A single muted monospaced line — a small leading glyph and middle-truncating text — for
/// surfacing a technical detail (a filename, a correlation id) without drawing attention. The
/// iOS-language counterpart of the mockup's `MonoLine`. Generic and domain-free.
struct MonospacedTechLine: View {
    var systemImage: String = "doc"
    let text: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: systemImage)
                .font(.caption.weight(.medium))
                .foregroundStyle(Color.luTint)
            Text(text)
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(Color.luLabel2)
                .lineLimit(1)
                .truncationMode(.middle)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityHidden(true)
    }
}

#Preview("MonospacedTechLine") {
    VStack(spacing: 14) {
        MonospacedTechLine(text: "2026-06-11T1501.audiobookshelf")
        MonospacedTechLine(systemImage: "number", text: "9bae4f91-1bb7-4c7b-91b9-dee991d5bf5e")
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
