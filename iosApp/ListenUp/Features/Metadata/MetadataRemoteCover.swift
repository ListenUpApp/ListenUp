import NukeUI
import SwiftUI

/// A plain remote cover image for metadata matches — these come from Audible / iTunes public URLs
/// (not the authenticated server cover endpoint), so a direct `LazyImage` is the right loader.
/// Renders a neutral book placeholder while loading or when no URL resolves.
struct MetadataRemoteCover: View {
    let url: String?

    var body: some View {
        LazyImage(url: url.flatMap(URL.init(string:))) { state in
            if let image = state.image {
                image.resizable().aspectRatio(contentMode: .fill)
            } else {
                ZStack {
                    Color.luFill
                    Image(systemName: "book.closed.fill")
                        .font(.system(size: 20))
                        .foregroundStyle(Color.luLabel3)
                }
            }
        }
    }
}

#Preview("MetadataRemoteCover") {
    MetadataRemoteCover(url: nil)
        .frame(width: 80, height: 80)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .padding()
        .background(Color.luSurface)
}
