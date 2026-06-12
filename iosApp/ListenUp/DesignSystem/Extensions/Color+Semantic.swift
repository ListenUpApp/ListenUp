import SwiftUI

/// Semantic color tokens for the clean-coral iOS language.
///
/// These map the design mockups' `--tokens` onto **native dynamic** colors, so light
/// and dark mode adapt automatically (the mockups hardcode hex; we don't). The brand
/// coral lives in `Color+ListenUp` as `listenUpOrange`; `luTint` aliases it so screen
/// code reads in the mockup's vocabulary. Primary label text uses native `.primary`.
extension Color {
    /// The single action tint (coral). Alias of `listenUpOrange`.
    static let luTint = Color.listenUpOrange
    /// Foreground on a coral fill.
    static let luOnTint = Color.white

    /// Grouped screen background (`--sys-bg`).
    static let luSurface = Color(.systemGroupedBackground)
    /// Inset-list / card surface (`--sys-bg-2`).
    static let luSurface2 = Color(.secondarySystemGroupedBackground)

    /// Hairline separator (`--separator`).
    static let luSeparator = Color(.separator)
    /// Neutral control fill (`--fill-3`).
    static let luFill = Color(.tertiarySystemFill)

    /// Secondary label (`--label-2`).
    static let luLabel2 = Color(.secondaryLabel)
    /// Tertiary label (`--label-3`).
    static let luLabel3 = Color(.tertiaryLabel)
}

// MARK: - Preview

#Preview("Tokens") {
    let swatches: [(String, Color)] = [
        ("luTint", .luTint), ("luOnTint", .luOnTint),
        ("luSurface", .luSurface), ("luSurface2", .luSurface2),
        ("luSeparator", .luSeparator), ("luFill", .luFill),
        ("luLabel2", .luLabel2), ("luLabel3", .luLabel3)
    ]
    return ScrollView {
        VStack(spacing: 12) {
            ForEach(swatches, id: \.0) { name, color in
                HStack(spacing: 16) {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(color)
                        .frame(width: 56, height: 36)
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.luSeparator))
                    Text(name).foregroundStyle(.primary)
                    Spacer()
                }
            }
        }
        .padding()
    }
    .background(Color.luSurface)
}
