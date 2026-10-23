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
    /// Coral text on a coral tint — the `CoralInk` Color Set: `#A8261F` light, `#FF9270` dark (`#8C1D18` / `#FFB8A3`
    /// under Increase Contrast). The brand coral itself is about 4:1 on its own 12% wash, under the 4.5:1 text floor
    /// (HIG, Accessibility), so a tinted capsule's label (Keep Listening, Listen Again) takes this deeper ink.
    static let luTintInk = Color(ColorResource.coralInk)

    /// Caution — the `WarningAmber` Color Set: `#8A5C00` light, `#FFD60A` dark (`#7A5200` / `#FFE55C`
    /// under Increase Contrast). A warning must not read as the brand coral, which means "act here",
    /// and system `.orange` sits beside it on the wheel (HIG, Color: "Avoid using the same color to
    /// mean different things"). System yellow is 1.5:1 on white, so the light value is a deep amber
    /// that holds 4.5:1 as text on every light surface (5.21:1 on `#F2F2F7`); pair it with
    /// `exclamationmark.triangle` so the meaning never rests on colour alone.
    static let luWarning = Color(ColorResource.warningAmber)

    /// A player failure the reader has to act on — the `PlayerErrorRed` Color Set: `#C4211A` light, `#FF6961` dark
    /// (`#8C1D18` / `#FF9A92` under Increase Contrast). The mini player draws its failed line in this red with a
    /// warning glyph on the plain system glass, never a tinted capsule (HIG, Tab bars: the accessory is system
    /// chrome). System red is 3.55:1 on white, under the 4.5:1 text floor (HIG, Accessibility), so the light value
    /// is deepened; the glyph keeps the meaning off colour alone.
    static let luPlayerError = Color(ColorResource.playerErrorRed)

    /// An armed sleep timer's container on Now Playing — the `SleepGoldContainer` Color Set: `#F6E1C4` light, `#4A3414`
    /// dark (`#3D2600` dark under Increase Contrast), the player spec's sleep gold (§3.9). Its countdown takes
    /// `luOnSleepGold`, 9:1 or more in every appearance (`BrandColorContrastTests`).
    static let luSleepGold = Color(ColorResource.sleepGoldContainer)
    /// The countdown's ink on `luSleepGold` — the `SleepGoldInk` Color Set: `#4A2E00` light, `#FFDDB0` dark.
    static let luOnSleepGold = Color(ColorResource.sleepGoldInk)
    /// A running timer's meter (the Sleep sheet's ring) on its `luSleepGold` track — the `SleepGoldMeter` Color Set:
    /// `#A3701F` light, `#E0B26A` dark (`#8A5C14` / `#F0C98A` under Increase Contrast), 3:1 or more on the track in every
    /// appearance as a meaningful graphic (`BrandColorContrastTests`). The board's `#B07A2A` is 2.9:1, so it is deepened.
    static let luSleepGoldMeter = Color(ColorResource.sleepGoldMeter)

    /// Secondary text on Now Playing's wash — the `OnWashSecondary` Color Set: `#57514D` light, `#ABA6A2` dark
    /// (`#3F3A37` / `#CFCAC6` under Increase Contrast). The system's secondary label is ~3.2:1 on the warm neutral and
    /// on a cover's tone-91 wash; this holds 4.5:1 on the neutral and every wash the shared cover rule can give
    /// (`NowPlayingWashTests`). For the status, the narrators, the times and the book sentence.
    static let luOnWashSecondary = Color(ColorResource.onWashSecondary)
    /// Disabled glyphs on Now Playing's wash — the `OnWashTertiary` Color Set, 3:1 or more on every wash.
    static let luOnWashTertiary = Color(ColorResource.onWashTertiary)

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
        ("luTint", .luTint), ("luOnTint", .luOnTint), ("luTintInk", .luTintInk),
        ("luWarning", .luWarning), ("luPlayerError", .luPlayerError),
        ("luSleepGold", .luSleepGold), ("luOnSleepGold", .luOnSleepGold), ("luSleepGoldMeter", .luSleepGoldMeter),
        ("luOnWashSecondary", .luOnWashSecondary), ("luOnWashTertiary", .luOnWashTertiary),
        ("luSurface", .luSurface), ("luSurface2", .luSurface2),
        ("luSeparator", .luSeparator), ("luFill", .luFill),
        ("luLabel3", .luLabel3)
    ]
    return ScrollView {
        VStack(spacing: 12) {
            ForEach(swatches, id: \.0) { name, color in
                HStack(spacing: 16) {
                    RoundedRectangle(cornerRadius: Radius.s)
                        .fill(color)
                        .frame(width: 56, height: 36)
                        .overlay(RoundedRectangle(cornerRadius: Radius.s).stroke(Color.luSeparator))
                    Text(name).foregroundStyle(.primary)
                    Spacer()
                }
            }
        }
        .padding()
    }
    .background(Color.luSurface)
}
