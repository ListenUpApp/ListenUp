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
    /// Text and glyphs on a coral fill — the `OnBrandCoral` Color Set: white in light, deep ink
    /// `#1C0A04` in dark, black under dark Increase Contrast. White on the dark coral `#FF6A3D` is
    /// only 2.85:1; the ink is 6.74:1 (HIG, Accessibility: 4.5:1 for text up to 17pt). Every label,
    /// glyph and spinner drawn on a coral fill takes this, never `.white`.
    static let luOnTint = Color(ColorResource.onBrandCoral)

    /// Caution — the `WarningAmber` Color Set: `#8A5C00` light, `#FFD60A` dark (`#7A5200` / `#FFE55C`
    /// under Increase Contrast). A warning must not read as the brand coral, which means "act here",
    /// and system `.orange` sits beside it on the wheel (HIG, Color: "Avoid using the same color to
    /// mean different things"). System yellow is 1.5:1 on white, so the light value is a deep amber
    /// that holds 4.5:1 as text on every light surface (5.21:1 on `#F2F2F7`); pair it with
    /// `exclamationmark.triangle` so the meaning never rests on colour alone.
    static let luWarning = Color(ColorResource.warningAmber)

    /// Grouped screen background (`--sys-bg`).
    static let luSurface = Color(.systemGroupedBackground)
    /// Inset-list / card surface (`--sys-bg-2`).
    static let luSurface2 = Color(.secondarySystemGroupedBackground)

    /// Hairline separator (`--separator`). Every custom hairline and card outline uses this rather
    /// than a `primary.opacity(_)` wash: the system separator has its own Increase Contrast variant,
    /// an opacity never does (HIG, Color: "Separator — a separator between different sections of
    /// content").
    static let luSeparator = Color(.separator)
    /// Neutral control fill (`--fill-3`).
    static let luFill = Color(.tertiarySystemFill)

    // Secondary and tertiary text use the hierarchical styles, `.foregroundStyle(.secondary)` and
    // `.tertiary`: on a material they pick up the system's vibrancy, which a fixed label colour never
    // does (HIG, Materials: "Help ensure legibility by using vibrant colors on top of materials").
    // Where a value must be a `Color` — a ternary against the tint, a fill — use `Color.secondary`.
    // Inside a `Button`, `Menu` or `Toggle` label, whose foreground is the tint, the hierarchical
    // style would inherit that tint, so those rows also name `Color.secondary` to stay grey.

    /// Tertiary label (`--label-3`) as a `Color`, for the places a hierarchical `.tertiary` can't go:
    /// SwiftUI has no `Color.tertiary`, and a `Button`/`Menu` label's `.tertiary` inherits the tint.
    /// Anywhere else, write `.foregroundStyle(.tertiary)`.
    static let luLabel3 = Color(.tertiaryLabel)
}

// MARK: - Preview

#Preview("Tokens") {
    let swatches: [(String, Color)] = [
        ("luTint", .luTint), ("luOnTint", .luOnTint), ("luWarning", .luWarning),
        ("luSurface", .luSurface), ("luSurface2", .luSurface2),
        ("luSeparator", .luSeparator), ("luFill", .luFill),
        ("luLabel3", .luLabel3)
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
