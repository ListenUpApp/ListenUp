import SwiftUI

extension View {
    /// The one prominent action of a view: the system's `.borderedProminent` at `.large`, filled with
    /// the accent and labelled in the on-coral ink.
    ///
    /// HIG, Buttons: "use a button that has a prominent visual style for the most likely action in a
    /// view … so the system can apply an accent color to the button's background", and "keep the
    /// number of prominent buttons to one or two per view". A system style brings the press state,
    /// the disabled appearance, Increase Contrast and the pointer effect with it — the hand-built
    /// `PrimaryButton` it replaces drew a coral fill that stayed coral while disabled.
    func prominentAction() -> some View {
        buttonStyle(.borderedProminent)
            .controlSize(.large)
            .onBrandFillLabel()
    }
}

/// A full-width button label that trades its content for a spinner while the action is in flight.
///
/// HIG, Buttons (iOS): "configure a button to display an activity indicator when you need to provide
/// feedback about an action that doesn't instantly complete". The title stays laid out underneath, so
/// the button keeps its size, and VoiceOver hears the title with a "Loading" value.
///
/// It is only a label: the caller's `Button` owns the style, and disables itself while busy.
struct ActionLabel: View {
    let title: String
    var systemImage: String?
    var isBusy = false

    var body: some View {
        ZStack {
            Group {
                if let systemImage {
                    Label(title, systemImage: systemImage)
                } else {
                    Text(title)
                }
            }
            .font(.headline)
            .opacity(isBusy ? 0 : 1)

            if isBusy {
                ProgressView()
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(isBusy ? String(localized: "common.accessibility_loading") : "")
    }
}

#Preview("Prominent actions") {
    VStack(spacing: 16) {
        Button {} label: { ActionLabel(title: "Continue Book 3", systemImage: "play.fill") }
            .prominentAction()
        Button {} label: { ActionLabel(title: "Sign In") }
            .prominentAction()
            .disabled(true)
        Button {} label: { ActionLabel(title: "Saving", isBusy: true) }
            .prominentAction()
            .disabled(true)
    }
    .padding()
}
