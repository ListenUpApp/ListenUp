import AVKit
import SwiftUI

/// SwiftUI wrapper for the system AirPlay route picker. Renders the standard
/// `AVRoutePickerView`; tapping it presents the system route chooser. Tinted to the
/// player accent so it sits with the other transport glyphs.
struct RoutePickerView: UIViewRepresentable {
    var tint: Color
    var activeTint: Color
    /// Where the audio is playing while it plays elsewhere ("Playing on Living Room"), spoken as the control's value.
    var playingOn: String?

    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.prioritizesVideoDevices = false
        picker.backgroundColor = .clear
        return picker
    }

    func updateUIView(_ picker: AVRoutePickerView, context: Context) {
        picker.tintColor = UIColor(tint)
        picker.activeTintColor = UIColor(activeTint)
        // The picker voices its own button; its value says where the audio goes, so the state isn't the tint alone.
        let views: [UIView] = [picker] + picker.subviews
        for view in views where view is UIControl || view === picker {
            view.accessibilityValue = playingOn
        }
    }
}
