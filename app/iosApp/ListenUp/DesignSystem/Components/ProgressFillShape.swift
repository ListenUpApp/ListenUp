import SwiftUI

/// Horizontal progress fill drawn purely in the render pass without `GeometryReader` layout passes.
///
/// Mirrors automatically in right-to-left layouts and animates `progress` smoothly via `animatableData`.
struct ProgressFillShape: Shape {
    var progress: Double
    var cornerRadius: CGFloat?
    var minWidth: CGFloat

    init(progress: Double, cornerRadius: CGFloat? = nil, minWidth: CGFloat = 0) {
        self.progress = progress
        self.cornerRadius = cornerRadius
        self.minWidth = minWidth
    }

    var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    var layoutDirectionBehavior: LayoutDirectionBehavior { .mirrors }

    func path(in rect: CGRect) -> Path {
        guard rect.width > 0, rect.height > 0 else { return Path() }
        let clamped = progress.isNaN ? 0 : min(max(progress, 0), 1)
        let floorWidth = minWidth.isFinite ? max(minWidth, 0) : 0
        let width = min(rect.width, max(rect.width * clamped, floorWidth))
        guard width > 0 else { return Path() }

        let fillRect = CGRect(x: rect.minX, y: rect.minY, width: width, height: rect.height)
        let resolvedRadius = cornerRadius ?? (rect.height / 2)
        let clampedRadius = min(max(resolvedRadius, 0), min(fillRect.width, fillRect.height) / 2)
        guard clampedRadius > 0 else { return Path(fillRect) }
        return Path(roundedRect: fillRect, cornerRadius: clampedRadius)
    }
}
