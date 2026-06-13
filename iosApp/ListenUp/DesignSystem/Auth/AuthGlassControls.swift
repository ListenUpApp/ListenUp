import SwiftUI

extension View {
    /// Applies the system Liquid Glass control material clipped to `shape`. When the
    /// user has Reduce Transparency on, falls back to an opaque tinted fill so controls
    /// stay legible (HIG accessibility requirement).
    func authGlassControl(in shape: some InsettableShape, reduceTransparency: Bool) -> some View {
        glassControl(in: shape, reduceTransparency: reduceTransparency)
    }
}

/// Floating top-left navigation pill (e.g. "Servers", "Back") — a glass control that
/// sits over the aurora. Tappable; tinted with the brand coral.
struct GlassNavPill: View {
    var systemImage: String = "chevron.left"
    var label: String
    var action: () -> Void

    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: systemImage)
                    .font(.subheadline.weight(.semibold))
                Text(label)
                    .font(.subheadline)
            }
            .foregroundStyle(Color.listenUpOrange)
            .padding(.leading, 11)
            .padding(.trailing, 15)
            .frame(height: 38)
            .authGlassControl(in: .capsule, reduceTransparency: reduceTransparency)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
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
            .padding(.horizontal, 13)
            .frame(height: 32)
            .authGlassControl(in: .capsule, reduceTransparency: reduceTransparency)
        }
        .buttonStyle(.plain)
        .disabled(isBusy)
    }
}
