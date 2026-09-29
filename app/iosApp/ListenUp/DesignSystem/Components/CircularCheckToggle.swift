import SwiftUI

/// The iOS selection idiom: a circular toggle that fills with the coral tint and shows a
/// checkmark when `isOn`, or renders a hollow neutral ring when off. Used wherever a row
/// offers an opt-in/opt-out choice (metadata field selection, chapter-rename review).
///
/// Generic and self-contained: the caller supplies `isOn` and an `action`; the toggle owns
/// only its visuals and a press animation. Pass a non-default `tint` to theme it per surface.
struct CircularCheckToggle: View {
    let isOn: Bool
    var tint: Color = .luTint
    /// Diameter of the circle. The checkmark scales with it.
    var size: CGFloat = 26
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            CircularCheckMark(isOn: isOn, tint: tint, size: size)
                .minimumTapTarget(visualSize: size)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isOn ? [.isSelected, .isButton] : .isButton)
    }
}

/// The toggle's look without its button — for a row that is itself the button. A button inside a
/// button is two controls stacked on one spot, and VoiceOver can reach only one of them (HIG,
/// VoiceOver), so a whole-row toggle draws this and carries the selected trait on the row.
struct CircularCheckMark: View {
    let isOn: Bool
    var tint: Color = .luTint
    var size: CGFloat = 26

    var body: some View {
        ZStack {
            if isOn {
                Circle().fill(tint)
                Image(systemName: "checkmark")
                    .font(.system(size: size * 0.55, weight: .heavy)) // decorative fixed size
                    .foregroundStyle(Color.luOnTint)
            } else {
                Circle()
                    .strokeBorder(Color.luLabel3, lineWidth: 2)
            }
        }
        .frame(width: size, height: size)
        .animation(.easeOut(duration: 0.15), value: isOn)
        .accessibilityHidden(true)
    }
}

#Preview("CircularCheckToggle") {
    struct Demo: View {
        @State private var a = true
        @State private var b = false
        var body: some View {
            HStack(spacing: 20) {
                CircularCheckToggle(isOn: a) { a.toggle() }
                CircularCheckToggle(isOn: b) { b.toggle() }
                CircularCheckToggle(isOn: true, size: 20) {}
            }
            .padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.luSurface)
        }
    }
    return Demo()
}
