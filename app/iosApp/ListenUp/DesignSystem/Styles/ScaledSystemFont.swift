import SwiftUI

/// A system font at a designed point size that still follows Dynamic Type.
///
/// Content text reaches for a text style first (`.largeTitle`…`.caption2`). When a design needs a
/// size between the styles — a hero percentage, an empty-state glyph — this scales that size with
/// the reader's text setting through `@ScaledMetric`, relative to the nearest text style. HIG,
/// Typography: "Make sure your app's layout adapts to all font sizes", and "If you use interface
/// icons to communicate important information, make sure they're easy to view at larger font sizes
/// too."
///
/// A literal `.system(size:)` anywhere else is reserved for a decorative glyph in a fixed-size
/// container and says so with `// decorative fixed size`; the `fixed_font_size` SwiftLint guard
/// fails the build otherwise. This modifier is the one sanctioned scaled route.
struct ScaledSystemFont: ViewModifier {
    @ScaledMetric private var size: CGFloat
    private let weight: Font.Weight
    private let design: Font.Design

    init(size: CGFloat, weight: Font.Weight, design: Font.Design, relativeTo textStyle: Font.TextStyle) {
        _size = ScaledMetric(wrappedValue: size, relativeTo: textStyle)
        self.weight = weight
        self.design = design
    }

    func body(content: Content) -> some View {
        content.font(.system(size: size, weight: weight, design: design)) // scaled via @ScaledMetric
    }
}

extension View {
    /// Sets a system font of `size` points at the default text size, scaled with Dynamic Type
    /// relative to `textStyle`. See `ScaledSystemFont`.
    func scaledFont(
        size: CGFloat,
        weight: Font.Weight = .regular,
        design: Font.Design = .default,
        relativeTo textStyle: Font.TextStyle
    ) -> some View {
        modifier(ScaledSystemFont(size: size, weight: weight, design: design, relativeTo: textStyle))
    }
}
