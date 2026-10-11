import ImageIO
import Testing
import UIKit
import UniformTypeIdentifiers
@testable import ListenUp

@Suite("PickedImage")
struct PickedImageTests {
    /// A solid-colour JPEG of exactly `width` x `height` stored pixels carrying an EXIF orientation.
    private func encodedImage(width: Int, height: Int, orientation: CGImagePropertyOrientation = .up) throws -> Data {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let cgImage = try #require(
            UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { context in
                UIColor.systemTeal.setFill()
                context.fill(CGRect(x: 0, y: 0, width: width, height: height))
            }.cgImage
        )
        let output = NSMutableData()
        let destination = try #require(
            CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil)
        )
        CGImageDestinationAddImage(
            destination,
            cgImage,
            [kCGImagePropertyOrientation: orientation.rawValue] as CFDictionary
        )
        try #require(CGImageDestinationFinalize(destination))
        return output as Data
    }

    /// Stored pixel size of encoded image bytes, read without applying orientation.
    private func storedPixelSize(of data: Data) throws -> (width: Int, height: Int) {
        let source = try #require(CGImageSourceCreateWithData(data as CFData, nil))
        let properties = try #require(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        let width = try #require(properties[kCGImagePropertyPixelWidth] as? Int)
        let height = try #require(properties[kCGImagePropertyPixelHeight] as? Int)
        return (width, height)
    }

    @Test func largeImageIsBoundedAndKeepsItsAspectRatio() throws {
        let jpeg = try #require(PickedImage.normalizedJPEG(from: encodedImage(width: 4_000, height: 3_000)))
        let size = try storedPixelSize(of: jpeg)
        #expect(max(size.width, size.height) <= PickedImage.maxEdgePixels)
        #expect(abs(Double(size.width) / Double(size.height) - 4.0 / 3.0) < 0.01)
        #expect(jpeg.starts(with: [0xFF, 0xD8, 0xFF]))
    }

    @Test func smallImageIsNotUpscaled() throws {
        let jpeg = try #require(PickedImage.normalizedJPEG(from: encodedImage(width: 400, height: 300)))
        let size = try storedPixelSize(of: jpeg)
        #expect(size.width == 400)
        #expect(size.height == 300)
    }

    @Test func rotatedPhotoComesBackUpright() throws {
        // Stored landscape 600x300 with orientation "rotate 90 degrees": displayed as 300x600.
        let jpeg = try #require(
            PickedImage.normalizedJPEG(from: encodedImage(width: 600, height: 300, orientation: .right))
        )
        let size = try storedPixelSize(of: jpeg)
        #expect(size.width == 300)
        #expect(size.height == 600)
    }

    @Test func bytesThatAreNotAnImageReturnNil() {
        #expect(PickedImage.normalizedJPEG(from: Data([0x00, 0x01, 0x02])) == nil)
    }
}
