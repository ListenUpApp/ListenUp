import SwiftUI

extension EnvironmentValues {
    /// Tells the shell that one of the full player's sheets (or its PDF reader) opened or closed, so the Playback menu's
    /// Close Player (Esc) waits while it is open and Esc closes it first. Nil outside the shell (previews, tests).
    @Entry var reportPlayerSheet: (@MainActor @Sendable (Bool) -> Void)?
}

extension View {
    /// Reports `isPresented` — a player sheet's own presentation flag — to the shell.
    func reportsPlayerSheet(_ isPresented: Bool) -> some View {
        modifier(PlayerSheetReporter(isPresented: isPresented))
    }
}

private struct PlayerSheetReporter: ViewModifier {
    let isPresented: Bool
    @Environment(\.reportPlayerSheet) private var report

    func body(content: Content) -> some View {
        content
            .onChange(of: isPresented) { _, open in report?(open) }
            .onDisappear { if isPresented { report?(false) } }
    }
}
