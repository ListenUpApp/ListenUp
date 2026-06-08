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

/// Full icon + wordmark lockup. Swaps to the white-wordmark variant in dark mode.
struct BrandLockup: View {
    var height: CGFloat = 112

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Image(scheme == .dark ? "BrandLockupDark" : "BrandLockupLight")
            .resizable()
            .scaledToFit()
            .frame(height: height)
            .accessibilityLabel("ListenUp")
    }
}
