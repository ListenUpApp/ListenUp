import SwiftUI

/// A determinate bar on a cover's colours: the cover track under the harmonised fill (Covers boards). Still a
/// `ProgressView`, so its value and semantics are the system's; used only when a hero has cover colours, the system
/// bar otherwise.
struct CoverTrackProgressStyle: ProgressViewStyle {
    let track: Color
    let fill: Color

    func makeBody(configuration: Configuration) -> some View {
        Capsule()
            .fill(track)
            .overlay {
                ProgressFillShape(progress: configuration.fractionCompleted ?? 0)
                    .fill(fill)
            }
            .frame(height: 5)
    }
}
