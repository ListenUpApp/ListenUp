import CoreGraphics
import Testing
@testable import ListenUp

/// Move and Scale's model over the shared `FrameEditor`: the gestures, the VoiceOver steps and the slider all
/// drive the same reducer, and the frame it holds is the one Done or Save stores.
@MainActor
@Suite("FrameEditorModel")
struct FrameEditorModelTests {
    /// A 600×800 portrait in a 340 pt cut-out, as on the iPhone board.
    private func model(initial: ImageFrameValue? = nil) -> FrameEditorModel {
        let model = FrameEditorModel(imageSize: CGSize(width: 600, height: 800), initial: initial)
        model.setViewport(340)
        return model
    }

    private func close(_ a: Double, _ b: Double) -> Bool { abs(a - b) < 1e-9 }

    @Test("starts at the centre crop when the photo has no frame")
    func startsCentred() {
        let model = model()
        #expect(close(model.frame.focusX, 0.5))
        #expect(close(model.frame.focusY, 0.5))
        #expect(close(model.frame.zoom, 1))
        #expect(model.isCentered)
    }

    @Test("starts at the saved frame")
    func startsAtSaved() {
        let model = model(initial: ImageFrameValue(focusX: 0.4, focusY: 0.45, zoom: 2))
        #expect(close(model.frame.focusX, 0.4))
        #expect(close(model.frame.zoom, 2))
        #expect(!model.isCentered)
    }

    @Test("a drag down shows more of the top")
    func dragDownShowsTop() {
        let model = model(initial: ImageFrameValue(focusX: 0.5, focusY: 0.5, zoom: 2))
        model.pan(by: CGSize(width: 0, height: 60))
        #expect(model.frame.focusY < 0.5)
    }

    @Test("Show more of the top moves the view up, as the decided call says")
    func nudgeUp() {
        let model = model(initial: ImageFrameValue(focusX: 0.5, focusY: 0.5, zoom: 2))
        model.nudge(.up)
        #expect(model.frame.focusY < 0.5)
        model.nudge(.right)
        #expect(model.frame.focusX > 0.5)
    }

    @Test("the adjustable increment zooms in by 1.15, the decrement zooms out, and both clamp")
    func adjustable() {
        let model = model()
        model.increment()
        #expect(close(model.frame.zoom, 1.15))
        model.decrement()
        model.decrement()
        #expect(close(model.frame.zoom, 1))
        for _ in 0..<20 { model.increment() }
        #expect(close(model.frame.zoom, 4))
    }

    @Test("the slider is logarithmic: its middle is 2×, and it reads the zoom back")
    func logSlider() {
        let model = model()
        model.sliderPosition = 0.5
        #expect(close(model.frame.zoom, 2))
        #expect(close(model.sliderPosition, 0.5))
        #expect(model.zoomText == "2.0×")
    }

    @Test("Reset returns to the centre crop")
    func reset() {
        let model = model(initial: ImageFrameValue(focusX: 0.3, focusY: 0.3, zoom: 3))
        model.reset()
        #expect(model.isCentered)
    }

    @Test("pinning against an edge bumps the haptic trigger once, not on every frame")
    func edgeTickOnce() async {
        let model = model(initial: ImageFrameValue(focusX: 0.5, focusY: 0.5, zoom: 1))
        let before = model.edgeTicks
        for _ in 0..<5 { model.pan(by: CGSize(width: 0, height: 500)) }
        try? await Task.sleep(for: .milliseconds(200))
        #expect(model.edgeTicks == before + 1)
    }

    @Test("the confirmed frame is canonical: a frame pinned at the top stores the focus actually shown")
    func confirmCanonical() {
        let model = model(initial: ImageFrameValue(focusX: 0.5, focusY: 0.0, zoom: 1))
        // A 600×800 photo just covering a square shows 3/4 of its height, so the centre sits 3/8 down.
        #expect(close(model.confirmedFrame().focusY, 0.375))
    }
}
