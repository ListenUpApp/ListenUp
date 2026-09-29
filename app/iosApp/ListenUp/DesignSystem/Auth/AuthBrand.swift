import SwiftUI

/// Square ListenUp icon mark (works on light & dark — its art is all coral/amber).
struct AppIconMark: View {
    var size: CGFloat = 56

    var body: some View {
        Image("BrandMark")
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .accessibilityLabel("ListenUp")
    }
}

/// Full icon + wordmark lockup. One imageset with a Dark appearance: the asset catalog picks the
/// white-wordmark art in dark mode, so the swap follows every trait change (and any view that
/// overrides the scheme) without code (HIG, Dark Mode: "Make sure full-color images and icons look
/// good in both appearances").
struct BrandLockup: View {
    var height: CGFloat = 112

    var body: some View {
        Image("BrandLockup")
            .resizable()
            .scaledToFit()
            .frame(height: height)
            .accessibilityLabel("ListenUp")
    }
}
