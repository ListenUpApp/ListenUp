import CoreImage
import SwiftUI
import UIKit

/// A legibility-clamped per-book accent derived from cover artwork.
///
/// `clamp` is the pure core (HSB constrain): floor saturation so a washed cover
/// doesn't read as grey mud, cap saturation so it stays subtle (never garish per the
/// iOS design language), and pull luminance into a mid band so it reads on BOTH light
/// and dark system backgrounds. `color` is the resulting SwiftUI `Color`.
struct CoverTint: Equatable {
    let hue: Double
    let saturation: Double
    let brightness: Double

    // Subtle-on-iOS band. Tuned conservatively; adjust with the sim pass.
    static let minSaturation = 0.25
    static let maxSaturation = 0.60
    static let minLuminance = 0.35
    static let maxLuminance = 0.62

    /// Rec.601 luminance of the clamped color (HSB→RGB result).
    var luminance: Double {
        let uiColor = UIColor(hue: hue, saturation: saturation, brightness: brightness, alpha: 1)
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        uiColor.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        return 0.299 * Double(red) + 0.587 * Double(green) + 0.114 * Double(blue)
    }

    var color: Color { Color(hue: hue, saturation: saturation, brightness: brightness) }

    static func clamp(red: Double, green: Double, blue: Double) -> CoverTint {
        var hue: CGFloat = 0, sat: CGFloat = 0, bri: CGFloat = 0, alpha: CGFloat = 0
        UIColor(red: red, green: green, blue: blue, alpha: 1)
            .getHue(&hue, saturation: &sat, brightness: &bri, alpha: &alpha)
        let clampedSaturation = min(max(Double(sat), minSaturation), maxSaturation)
        var brightness = min(max(Double(bri), minLuminance), maxLuminance)
        var tint = CoverTint(hue: Double(hue), saturation: clampedSaturation, brightness: brightness)
        // A saturated HSB color's luminance is strictly below its brightness, so a single
        // additive nudge undershoots the band (e.g. near-black never reaches minLuminance).
        // Iterate the correction until luminance lands in band, bounded to avoid any cycle.
        for _ in 0..<24 {
            let luminance = tint.luminance
            let next: Double
            if luminance < minLuminance {
                next = min(1.0, brightness + (minLuminance - luminance) + 0.005)
            } else if luminance > maxLuminance {
                next = max(0.0, brightness - (luminance - maxLuminance) - 0.005)
            } else {
                break
            }
            if next == brightness { break }
            brightness = next
            tint = CoverTint(hue: Double(hue), saturation: clampedSaturation, brightness: brightness)
        }
        return tint
    }
}

/// Resolves (and caches) a `CoverTint` for a book's cover. Async decode off the main
/// actor; the screen renders coral until this resolves, and falls back to coral on any
/// failure (never-stranded). Cache keyed by bookId so re-entry never flickers.
@MainActor
final class CoverTintExtractor {
    static let shared = CoverTintExtractor()
    private var cache: [String: CoverTint] = [:]

    func cached(bookId: String) -> CoverTint? { cache[bookId] }

    func resolve(bookId: String, coverPath: String?) async -> CoverTint? {
        if let hit = cache[bookId] { return hit }
        guard let coverPath, let rgb = await Self.averageRGB(coverPath: coverPath) else { return nil }
        let tint = CoverTint.clamp(red: rgb.0, green: rgb.1, blue: rgb.2)
        cache[bookId] = tint
        return tint
    }

    /// Average RGB of a tiny downsample of the cover. Returns nil if it can't decode.
    ///
    /// Decodes a ≤32px thumbnail through the shared `ImageDownsampler` seam (so the
    /// full-resolution bitmap is never materialized), then reduces its full extent to a
    /// single averaged pixel via `CIAreaAverage` and reads that pixel back. CPU work, kept
    /// off the main actor.
    nonisolated static func averageRGB(coverPath: String) async -> (Double, Double, Double)? {
        guard let thumbnail = ImageDownsampler.downsampledImage(atPath: coverPath, maxPixelSize: 32),
              let cgImage = thumbnail.cgImage
        else { return nil }

        let inputImage = CIImage(cgImage: cgImage)
        let extent = inputImage.extent
        guard extent.width > 0, extent.height > 0,
              let averageFilter = CIFilter(
                  name: "CIAreaAverage",
                  parameters: [
                      kCIInputImageKey: inputImage,
                      kCIInputExtentKey: CIVector(cgRect: extent)
                  ]
              ),
              let outputImage = averageFilter.outputImage
        else { return nil }

        var pixel = [UInt8](repeating: 0, count: 4)
        let context = CIContext(options: [.workingColorSpace: NSNull()])
        context.render(
            outputImage,
            toBitmap: &pixel,
            rowBytes: 4,
            bounds: CGRect(x: 0, y: 0, width: 1, height: 1),
            format: .RGBA8,
            colorSpace: CGColorSpaceCreateDeviceRGB()
        )

        return (Double(pixel[0]) / 255.0, Double(pixel[1]) / 255.0, Double(pixel[2]) / 255.0)
    }
}
