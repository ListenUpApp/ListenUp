import SwiftUI

/// A rounded-square icon tile — an SF Symbol on a soft fill. The recurring leading glyph for
/// setting / management / option / field rows, app-wide. The mockups name this `IconTile`.
///
/// Three fill modes:
/// - `.tonal` (default) — a soft `tint.opacity(0.14)` fill with the symbol drawn in `tint`.
///   The clean-coral language used by management rows, access-level cards, and selected fields.
/// - `.solid` — a saturated `tint` fill with a white symbol, for emphasis (e.g. the
///   invite-preview avatar).
/// - `.inactive` — a neutral grey fill with a muted symbol, for deselected/unavailable rows
///   (e.g. a metadata field that won't be applied).
///
/// Sizes flow from `size`; the corner radius and glyph weight scale with it so the tile
/// reads correctly from a 30pt row glyph up to a 48pt hero badge.
struct IconTile: View {
    enum Style {
        case tonal
        case solid
        case inactive
    }

    let systemImage: String
    var tint: Color = .luTint
    var size: CGFloat = 30
    var style: Style = .tonal

    /// Convenience for field rows: `true` reads as `.tonal`, `false` as `.inactive`.
    init(systemImage: String, isActive: Bool, tint: Color = .luTint, size: CGFloat = 30) {
        self.systemImage = systemImage
        self.tint = tint
        self.size = size
        self.style = isActive ? .tonal : .inactive
    }

    init(systemImage: String, tint: Color = .luTint, size: CGFloat = 30, style: Style = .tonal) {
        self.systemImage = systemImage
        self.tint = tint
        self.size = size
        self.style = style
    }

    private var cornerRadius: CGFloat { size * 0.3 }
    private var glyphSize: CGFloat { size * 0.5 }

    private var fillStyle: AnyShapeStyle {
        switch style {
        case .solid: return AnyShapeStyle(tint)
        case .tonal: return AnyShapeStyle(tint.opacity(0.14))
        case .inactive: return AnyShapeStyle(Color.luFill)
        }
    }

    private var glyphColor: Color {
        switch style {
        case .solid: return .white
        case .tonal: return tint
        case .inactive: return .luLabel3
        }
    }

    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            .fill(fillStyle)
            .frame(width: size, height: size)
            .overlay {
                Image(systemName: systemImage)
                    .font(.system(size: glyphSize, weight: .semibold))
                    .foregroundStyle(glyphColor)
            }
            .accessibilityHidden(true)
    }
}

#Preview("IconTile") {
    HStack(spacing: 16) {
        IconTile(systemImage: "person.2.fill", tint: .green)
        IconTile(systemImage: "shield.fill", tint: .luTint)
        IconTile(systemImage: "square.grid.2x2.fill", tint: .blue, size: 40)
        IconTile(systemImage: "mic", isActive: false)
        IconTile(systemImage: "link", tint: .luTint, size: 48, style: .solid)
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
