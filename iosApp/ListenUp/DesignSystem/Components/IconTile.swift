import SwiftUI

/// A tonal rounded-square icon tile — an accent-tinted SF Symbol on a soft accent fill.
///
/// The recurring leading glyph for setting / management / option rows, app-wide. The
/// mockup names this `IconTile`; this is its native form. Two fill modes:
/// - `.tonal` (default) — a soft `tint.opacity(0.14)` fill with the symbol drawn in `tint`.
///   The clean-coral language used by management rows and access-level cards.
/// - `.solid` — a saturated `tint` fill with a white symbol, for emphasis (e.g. the
///   invite-preview avatar).
///
/// Sizes flow from `size`; the corner radius and glyph weight scale with it so the tile
/// reads correctly from a 30pt row glyph up to a 48pt hero badge.
struct IconTile: View {
    enum Style {
        case tonal
        case solid
    }

    let systemImage: String
    var tint: Color = .luTint
    var size: CGFloat = 30
    var style: Style = .tonal

    private var cornerRadius: CGFloat { size * 0.3 }
    private var glyphSize: CGFloat { size * 0.5 }

    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            .fill(style == .solid ? AnyShapeStyle(tint) : AnyShapeStyle(tint.opacity(0.14)))
            .frame(width: size, height: size)
            .overlay {
                Image(systemName: systemImage)
                    .font(.system(size: glyphSize, weight: .semibold))
                    .foregroundStyle(style == .solid ? Color.white : tint)
            }
            .accessibilityHidden(true)
    }
}

#Preview("IconTile") {
    HStack(spacing: 16) {
        IconTile(systemImage: "person.2.fill", tint: .green)
        IconTile(systemImage: "shield.fill", tint: .luTint)
        IconTile(systemImage: "square.grid.2x2.fill", tint: .blue, size: 40)
        IconTile(systemImage: "link", tint: .luTint, size: 48, style: .solid)
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
