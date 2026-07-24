import SwiftUI

extension View {
    /// Applies the system Liquid Glass control material clipped to `shape`, for FLOATING chrome
    /// (toolbars/pills/bars/overlays over content). Falls back to an opaque tinted fill under
    /// Reduce Transparency (HIG accessibility). Do NOT use on content surfaces — those stay opaque.
    @ViewBuilder
    func glassControl(in shape: some InsettableShape, reduceTransparency: Bool) -> some View {
        if reduceTransparency {
            background(Color(.secondarySystemBackground), in: shape)
                .overlay(shape.strokeBorder(Color.primary.opacity(0.12), lineWidth: 0.5))
        } else {
            glassEffect(.regular, in: shape)
        }
    }
}

/// Reads Reduce Transparency from the environment so call sites stay terse.
struct GlassControlModifier<S: InsettableShape>: ViewModifier {
    let shape: S
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    func body(content: Content) -> some View {
        content.glassControl(in: shape, reduceTransparency: reduceTransparency)
    }
}

extension View {
    /// Liquid Glass for floating chrome, reading Reduce Transparency from the environment.
    func glassControl(in shape: some InsettableShape) -> some View { modifier(GlassControlModifier(shape: shape)) }
}
