import Foundation

/// 1-based page for a scrubber fraction (0...1), clamped. `pageCount == 0` → 0.
func scrubberPage(fraction: Double, pageCount: Int) -> Int {
    guard pageCount > 0 else { return 0 }
    let clampedFraction = min(max(fraction, 0), 1)
    let page = Int((clampedFraction * Double(pageCount)).rounded())
    return min(max(page, 1), pageCount)
}
