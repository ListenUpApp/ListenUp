import SwiftUI

/// The crossfade from the neutral hero to a cover's colours (spec §2.3: ≤ 200 ms): only for colours that arrived after
/// a cache miss, and never under Reduce Motion (HIG, Accessibility: "Motion").
enum CoverThemeMotion {
    static func animation(fadesIn: Bool, reduceMotion: Bool) -> Animation? {
        fadesIn && !reduceMotion ? .easeOut(duration: 0.2) : nil
    }
}
