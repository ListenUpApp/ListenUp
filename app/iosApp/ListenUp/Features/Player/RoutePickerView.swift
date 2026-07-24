import AVKit
import SwiftUI

/// SwiftUI wrapper for the system AirPlay route picker. Renders the standard
/// `AVRoutePickerView`; tapping it presents the system route chooser. Tinted to the
/// player accent so it sits with the other transport glyphs.
struct RoutePickerView: UIViewRepresentable {
    var tint: Color
    var activeTint: Color

    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.prioritizesVideoDevices = false
        picker.backgroundColor = .clear
        return picker
    }

    func updateUIView(_ picker: AVRoutePickerView, context: Context) {
        picker.tintColor = UIColor(tint)
        picker.activeTintColor = UIColor(activeTint)
    }
}
