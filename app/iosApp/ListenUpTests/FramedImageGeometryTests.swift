import CoreGraphics
import ListenupContract
import Testing
@testable import ListenUp

/// Where a framed photo is drawn inside its box. The maths is the shared `frameLayout`; these pin that the iOS
/// wrapper hands it the right sizes and reads the right numbers back.
@Suite("FramedImageGeometry")
struct FramedImageGeometryTests {
    @Test("no frame is aspect-fill, centred: today's centre crop")
    func centreCropWhenUnframed() {
        let rect = FramedImageGeometry.rect(
            imageSize: CGSize(width: 300, height: 400),
            box: CGSize(width: 120, height: 120),
            frame: nil
        )
        #expect(abs(rect.size.width - 120) < 0.001)
        #expect(abs(rect.size.height - 160) < 0.001)
        #expect(abs(rect.origin.x) < 0.001)
        #expect(abs(rect.origin.y - -20) < 0.001)
    }

    @Test("a frame focused high pins the top edge of a portrait")
    func focusHighPinsTop() {
        let frame = ImageFrameValue(focusX: 0.5, focusY: 0.0, zoom: 1.0)
        let rect = FramedImageGeometry.rect(
            imageSize: CGSize(width: 300, height: 400),
            box: CGSize(width: 120, height: 120),
            frame: frame
        )
        #expect(abs(rect.origin.y) < 0.001)
    }

    @Test("the rect is scale-invariant: one frame looks the same at 48 and 132 points")
    func scaleInvariant() {
        let frame = ImageFrameValue(focusX: 0.4, focusY: 0.3, zoom: 2.2)
        let image = CGSize(width: 600, height: 800)
        let small = FramedImageGeometry.rect(imageSize: image, box: CGSize(width: 48, height: 48), frame: frame)
        let large = FramedImageGeometry.rect(imageSize: image, box: CGSize(width: 132, height: 132), frame: frame)
        #expect(abs(small.origin.x / 48 - large.origin.x / 132) < 1e-9)
        #expect(abs(small.origin.y / 48 - large.origin.y / 132) < 1e-9)
        #expect(abs(small.size.width / 48 - large.size.width / 132) < 1e-9)
    }

    @Test("a zoomed frame draws the photo larger than the box, and never leaves a gap")
    func zoomCovers() {
        let rect = FramedImageGeometry.rect(
            imageSize: CGSize(width: 600, height: 800),
            box: CGSize(width: 100, height: 100),
            frame: ImageFrameValue(focusX: 1, focusY: 1, zoom: 3)
        )
        #expect(abs(rect.width - 300) < 0.001)
        #expect(rect.maxX >= 100 - 0.001)
        #expect(rect.maxY >= 100 - 0.001)
        #expect(rect.minX <= 0.001)
        #expect(rect.minY <= 0.001)
    }

    @Test("the decode target grows with the zoom and caps at 2,048 px")
    func zoomedTarget() {
        #expect(FramedImageGeometry.targetPixels(base: 360, frame: ImageFrameValue(focusX: 0.5, focusY: 0.5, zoom: 2.2)) == 792)
        #expect(FramedImageGeometry.targetPixels(base: 1200, frame: ImageFrameValue(focusX: 0.5, focusY: 0.5, zoom: 4)) == 2048)
        #expect(FramedImageGeometry.targetPixels(base: 360, frame: nil) == 360)
    }

    @Test("a Kotlin frame projects to the same numbers, and nil stays nil")
    func projection() {
        let value = ImageFrameValue(ImageFrame(focusX: 0.25, focusY: 0.75, zoom: 1.5))
        #expect(value == ImageFrameValue(focusX: 0.25, focusY: 0.75, zoom: 1.5))
        #expect(ImageFrameValue(nil as ImageFrame?) == nil)
        let back = ImageFrameValue(focusX: 0.1, focusY: 0.2, zoom: 3).kotlin
        #expect(back.focusX == 0.1)
        #expect(back.focusY == 0.2)
        #expect(back.zoom == 3)
    }
}
