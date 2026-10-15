import Foundation
import ListenupContract
import Observation
import Shared

/// Move and Scale's state for SwiftUI. It wraps the shared `FrameEditor` reducer, so iOS edits a frame with
/// exactly the clamp Android and the web use, and projects the frame into a native value after every step.
/// The only place the editor touches Kotlin.
@Observable
@MainActor
final class FrameEditorModel {
    /// The frame being edited; always canonical, so it is exactly what Done or Save stores.
    private(set) var frame: ImageFrameValue
    /// Bumped once per rising edge (a photo edge or a zoom limit): the haptic trigger.
    private(set) var edgeTicks = 0
    /// The photo's size, in the units the stage lays it out in.
    let imageSize: CGSize

    private let editor: FrameEditor
    private let bridge = FlowBridge()

    /// One accessibility zoom step: increment and decrement multiply or divide by this.
    static let zoomStep = 1.15

    init(imageSize: CGSize, initial: ImageFrameValue?) {
        self.imageSize = imageSize
        let editor = FrameEditor(
            imageWidth: Int32(max(1, imageSize.width.rounded())),
            imageHeight: Int32(max(1, imageSize.height.rounded())),
            initialFrame: initial?.kotlin,
            shape: .Circle
        )
        self.editor = editor
        frame = Self.project(editor)
        // By name: a flow of Kotlin enum entries traps in the Swift Export bridge.
        bridge.bind(editor.feedbackNames) { [weak self] _ in self?.edgeTicks += 1 }
    }

    /// The cut-out's side, in the units every gesture is given in. Call on first layout and on every resize.
    func setViewport(_ side: CGFloat) { editor.setViewport(size: Double(side)) }

    /// A drag by `delta`: the photo follows the finger.
    func pan(by delta: CGSize) {
        editor.pan(dx: Double(delta.width), dy: Double(delta.height))
        sync()
    }

    /// A pinch by `factor` about `anchor`, in the cut-out's coordinates.
    func zoom(by factor: CGFloat, anchor: CGPoint) {
        editor.zoomBy(factor: Double(factor), anchorX: Double(anchor.x), anchorY: Double(anchor.y))
        sync()
    }

    /// VoiceOver's adjustable increment: one step closer.
    func increment() {
        editor.zoomTo(zoom: frame.zoom * Self.zoomStep)
        sync()
    }

    /// VoiceOver's adjustable decrement: one step further away.
    func decrement() {
        editor.zoomTo(zoom: frame.zoom / Self.zoomStep)
        sync()
    }

    /// Back to the centre crop.
    func reset() {
        editor.reset()
        sync()
    }

    /// Which part of the photo a named nudge shows more of.
    enum Direction { case up, down, left, right }

    /// One VoiceOver nudge: `.up` shows more of the photo's top.
    func nudge(_ direction: Direction) {
        let kotlin: NudgeDirection = switch direction {
        case .up: .Up
        case .down: .Down
        case .left: .Left
        case .right: .Right
        }
        editor.nudge(direction: kotlin, large: false)
        sync()
    }

    /// The slider's position, `0...1`, on the shared logarithmic scale: its middle is 2×.
    var sliderPosition: Double {
        get { ExportedKotlinPackages.com.calypsan.listenup.client.core.image.sliderPositionForZoom(zoom: frame.zoom) }
        set {
            editor.zoomTo(zoom: ExportedKotlinPackages.com.calypsan.listenup.client.core.image
                .zoomForSliderPosition(position: newValue))
            sync()
        }
    }

    /// The zoom as people read it, "2.2×".
    var zoomText: String { ExportedKotlinPackages.com.calypsan.listenup.client.core.image.zoomLabel(zoom: frame.zoom) }

    /// Whether the frame is already the centre crop, so Reset has nothing to do.
    var isCentered: Bool {
        abs(frame.focusX - 0.5) < 1e-9 && abs(frame.focusY - 0.5) < 1e-9 && abs(frame.zoom - 1) < 1e-9
    }

    /// The frame to stage or save. The reducer keeps it canonical (spec §6.2), so what was shown is what is
    /// stored.
    func confirmedFrame() -> ImageFrameValue { frame }

    private func sync() { frame = Self.project(editor) }

    private static func project(_ editor: FrameEditor) -> ImageFrameValue {
        let current = editor.state.value.frame
        return ImageFrameValue(focusX: current.focusX, focusY: current.focusY, zoom: current.zoom)
    }
}
