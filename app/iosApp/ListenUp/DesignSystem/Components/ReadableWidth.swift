import SwiftUI

extension View {
    /// Caps content to a comfortable reading width and centers it, so single-column content
    /// (placeholders, profile/search screens) doesn't stretch edge-to-edge on iPad and
    /// large/resized windows. A no-op when the container is already narrower than `maxWidth`,
    /// so it's safe to apply unconditionally — the cap only bites once there's excess width.
    /// For a `List` or `Form`, use `readableListWidth(_:)`: a frame would clip the scroll view.
    func readableWidth(_ maxWidth: CGFloat = 640) -> some View {
        frame(maxWidth: maxWidth)
            .frame(maxWidth: .infinity)
    }

    /// Gives a grouped `List`/`Form` a readable column on wide widths by widening its horizontal
    /// scroll-content margins from the width it actually gets — so it stays right in Split View,
    /// Stage Manager and full-screen iPad alike (iosApp rule 12). An inset-grouped list does not cap
    /// itself: measured on iOS 26, its rows run 1296pt wide in a 1376pt window. Below the cap the
    /// system's own margins stand. HIG, Layout: "Make sure text is readable at every size".
    func readableListWidth(_ maxWidth: CGFloat = 640) -> some View {
        modifier(ReadableListWidthModifier(maxWidth: maxWidth))
    }
}

/// The margin rule behind `readableListWidth`, kept pure so it is unit-tested.
enum ReadableListWidth {
    /// The system's own leading/trailing margin for a grouped list, below which we never go.
    static let systemMargin: CGFloat = 20

    /// The horizontal content margin that centres a `maxWidth` column in `containerWidth`, or `nil`
    /// (keep the system margin) when the container isn't wide enough for the cap to bite.
    static func margin(containerWidth: CGFloat, maxWidth: CGFloat) -> CGFloat? {
        let centred = (containerWidth - maxWidth) / 2
        return centred > systemMargin ? centred : nil
    }
}

private struct ReadableListWidthModifier: ViewModifier {
    let maxWidth: CGFloat
    @State private var containerWidth: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .contentMargins(
                .horizontal,
                ReadableListWidth.margin(containerWidth: containerWidth, maxWidth: maxWidth),
                for: .scrollContent
            )
            .onGeometryChange(for: CGFloat.self, of: { $0.size.width }, action: { containerWidth = $0 })
    }
}
