import Foundation
import ListenupContract

/// How a photo sits in its shape, as a native value: the point at the shape's centre (0...1 of the photo's
/// width and height) and the zoom past "just covers" (1...4). Projected once from the Kotlin `ImageFrame` at
/// an observer boundary (iosApp rule 8), so views and list diffs never re-bridge it.
struct ImageFrameValue: Equatable, Hashable, Sendable {
    let focusX: Double
    let focusY: Double
    let zoom: Double

    /// Today's centre crop, as a frame.
    static let centered = ImageFrameValue(focusX: 0.5, focusY: 0.5, zoom: 1)

    init(focusX: Double, focusY: Double, zoom: Double) {
        self.focusX = focusX
        self.focusY = focusY
        self.zoom = zoom
    }

    /// The native copy of a Kotlin frame; nil for no frame (the centre crop).
    init?(_ frame: ImageFrame?) {
        guard let frame else { return nil }
        self.init(focusX: frame.focusX, focusY: frame.focusY, zoom: frame.zoom)
    }

    /// Back across the boundary, for the shared layout and for a write.
    var kotlin: ImageFrame { ImageFrame(focusX: focusX, focusY: focusY, zoom: zoom) }
}
