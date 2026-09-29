import SwiftUI

extension View {
    /// Applies the system Liquid Glass control material clipped to `shape`. When the
    /// user has Reduce Transparency on, falls back to an opaque tinted fill so controls
    /// stay legible (HIG accessibility requirement).
    func authGlassControl(in shape: some InsettableShape, reduceTransparency: Bool) -> some View {
        glassControl(in: shape, reduceTransparency: reduceTransparency)
    }
}

/// Small glass capsule for the Select-Server "Rescan" action.
struct RescanPill: View {
    var isBusy: Bool
    var action: () -> Void

    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if isBusy {
                    ProgressView().controlSize(.mini).tint(Color.listenUpOrange)
                } else {
                    Image(systemName: "arrow.clockwise").font(.footnote.weight(.semibold))
                }
                Text(String(localized: "connect.rescan"))
                    .font(.subheadline)
            }
            .foregroundStyle(Color.listenUpOrange)
            .padding(.horizontal, Spacing.s)
            .frame(height: 32)
            .authGlassControl(in: .capsule, reduceTransparency: reduceTransparency)
        }
        .buttonStyle(.plain)
        .disabled(isBusy)
    }
}
