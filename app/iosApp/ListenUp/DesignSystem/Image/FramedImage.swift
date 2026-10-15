import Shared
import SwiftUI

/// Where to draw a framed photo inside its box. A thin wrapper over the shared `frameLayout`, so iOS,
/// Android and the web place one frame identically; this file never re-derives the maths.
enum FramedImageGeometry {
    /// The largest decode a framed photo asks for, in pixels.
    static let maxDecodePixels: CGFloat = 2048

    /// The rect, in the box's own coordinates, to draw an image of `imageSize` in so it covers `box` the way
    /// `frame` says. With no frame it is aspect-fill, centred: today's crop.
    static func rect(imageSize: CGSize, box: CGSize, frame: ImageFrameValue?) -> CGRect {
        let layout = ExportedKotlinPackages.com.calypsan.listenup.client.core.image.frameLayout(
            imageWidth: Double(imageSize.width),
            imageHeight: Double(imageSize.height),
            viewportWidth: Double(box.width),
            viewportHeight: Double(box.height),
            frame: frame?.kotlin
        )
        return CGRect(x: layout.offsetX, y: layout.offsetY, width: layout.width, height: layout.height)
    }

    /// How many pixels to decode for a box that needs `base` unzoomed: a zoomed frame shows a smaller part of
    /// the photo, so it needs proportionally more of it to stay sharp. Capped at ``maxDecodePixels``.
    static func targetPixels(base: CGFloat, frame: ImageFrameValue?) -> CGFloat {
        min(maxDecodePixels, (base * CGFloat(frame?.zoom ?? 1)).rounded())
    }
}

/// A photo drawn the way its frame says, clipped to the box it is given. With no frame it is exactly
/// `.aspectRatio(contentMode: .fill)` centred: today's crop. The caller clips to its shape.
struct FramedImage: View {
    let image: UIImage
    let frame: ImageFrameValue?

    var body: some View {
        GeometryReader { proxy in
            let rect = FramedImageGeometry.rect(imageSize: image.size, box: proxy.size, frame: frame)
            Image(uiImage: image)
                .resizable()
                .frame(width: rect.width, height: rect.height)
                .offset(x: rect.minX, y: rect.minY)
                .frame(width: proxy.size.width, height: proxy.size.height, alignment: .topLeading)
                .clipped()
        }
        .accessibilityHidden(true)
    }
}
