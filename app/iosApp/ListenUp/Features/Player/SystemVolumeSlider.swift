import MediaPlayer
import SwiftUI

/// The system volume slider. HIG, Playing audio: "Use the system-provided volume view to let
/// people make audio adjustments", and HIG, Sliders: "Don't use a slider to adjust audio volume"
/// — so this is `MPVolumeView`, not a SwiftUI `Slider`. It speaks for itself to VoiceOver and
/// follows the hardware buttons; AirPlay stays with `RoutePickerView` in the secondary row.
struct SystemVolumeSlider: View {
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "speaker.fill")
                .accessibilityHidden(true)
            #if targetEnvironment(simulator)
            SimulatorVolumeStandIn()
                .frame(height: 34)
            #else
            VolumeView()
                .frame(height: 34)
            #endif
            Image(systemName: "speaker.wave.3.fill")
                .accessibilityHidden(true)
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
    }
}

private struct VolumeView: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView {
        let view = MPVolumeView(frame: .zero)
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ uiView: MPVolumeView, context: Context) {}
}

#if targetEnvironment(simulator)
/// The simulator has no audio route, so `MPVolumeView` draws nothing there and the row shows two
/// speakers with a gap between them, in development and in the App Store screenshots alike. This
/// stands in with a slider of the same shape; it never ships to a device.
private struct SimulatorVolumeStandIn: View {
    @State private var level = 0.6

    var body: some View {
        Slider(value: $level)
            .tint(.secondary)
    }
}
#endif
