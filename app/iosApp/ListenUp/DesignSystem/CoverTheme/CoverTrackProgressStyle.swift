import SwiftUI

/// A determinate bar on a cover's colours: the cover track under the harmonised fill (Covers boards). Still a
/// `ProgressView`, so its value and semantics are the system's; used only when a hero has cover colours, the system
/// bar otherwise.
struct CoverTrackProgressStyle: ProgressViewStyle {
    let track: Color
    let fill: Color

    func makeBody(configuration: Configuration) -> some View {
        let fraction = min(max(configuration.fractionCompleted ?? 0, 0), 1)
        Capsule()
            .fill(track)
            .frame(height: 5)
            .overlay(alignment: .leading) {
                GeometryReader { proxy in
                    Capsule().fill(fill).frame(width: proxy.size.width * fraction)
                }
            }
            .frame(height: 5)
    }
}
