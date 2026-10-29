import SwiftUI

/// The full player's presentation: a system full-screen cover that zooms out of the mini player's cover
/// (`PlayerTransition`).
///
/// A full-screen modal (HIG, Modality: "a full-screen modal style for in-depth content") brings VoiceOver modality,
/// the interactive swipe-down and the zoom back into the mini player with it. `MainTabView` and
/// `PlayerZoomTransitionTests` share this one modifier, so the test measures the zoom production plays.
struct PlayerPresentation<Player: View>: ViewModifier {
    @Binding var isPresented: Bool
    /// The namespace the mini player's cover registers in as the zoom source.
    let namespace: Namespace.ID
    /// Runs once the cover has gone, after the zoom back into the mini player.
    let onDismiss: () -> Void
    @ViewBuilder let player: () -> Player

    func body(content: Content) -> some View {
        content.fullScreenCover(isPresented: $isPresented, onDismiss: onDismiss) {
            player()
                .navigationTransition(.zoom(sourceID: PlayerTransition.coverID, in: namespace))
        }
    }
}

extension View {
    /// Presents the full player as a full-screen cover that zooms out of the mini player's cover.
    func playerPresentation<Player: View>(
        isPresented: Binding<Bool>,
        namespace: Namespace.ID,
        onDismiss: @escaping () -> Void = {},
        @ViewBuilder player: @escaping () -> Player
    ) -> some View {
        modifier(PlayerPresentation(
            isPresented: isPresented, namespace: namespace, onDismiss: onDismiss, player: player))
    }
}
