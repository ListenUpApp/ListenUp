import Foundation
import Testing
import UIKit
@testable import ListenUp

/// `BlurHashView` decodes the BlurHash OFF the main thread (a synchronous decode in `body`
/// froze the app during fast cover-grid scrolling — the alphabet-scrubber hang). Moving it
/// off-main means many cover cells decode CONCURRENTLY during a scroll, so the decoder must be
/// safe under concurrency. These pin that contract.
@Suite("BlurHash decode", .serialized)
struct BlurHashDecodeTests {
    // The same representative hash used in the BlurHashView preview.
    private let hash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj"
    private let size = CGSize(width: 32, height: 32)

    @Test func decodesAValidHashToAnImage() {
        #expect(UIImage(blurHash: hash, size: size) != nil)
    }

    @Test func concurrentDecodesAllSucceed() async {
        let hash = self.hash
        let size = self.size
        let succeeded = await withTaskGroup(of: Bool.self) { group in
            for _ in 0 ..< 64 {
                group.addTask { UIImage(blurHash: hash, size: size) != nil }
            }
            var count = 0
            for await ok in group where ok { count += 1 }
            return count
        }
        #expect(succeeded == 64)
    }
}
