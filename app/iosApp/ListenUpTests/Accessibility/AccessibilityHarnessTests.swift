import SwiftUI
import Testing
@testable import ListenUp

/// The harness itself: a hosted view is read only once the accessibility runtime has fully arrived (#1578).
@MainActor
@Suite("Accessibility harness")
struct AccessibilityHarnessTests {
    /// SwiftUI's accessibility bundle loads seconds after automation is switched on, and 30 s or more later on a
    /// loaded CI runner. A tree read before it lands came back empty, or a stop short with stale frames.
    @Test func aHostedViewIsReadOnlyOnceSwiftUIAccessibilityHasLoaded() async {
        let hosted = await HostedView(Text(verbatim: "Ready"))
        defer { hosted.close() }
        #expect(HostedView.isSwiftUIAccessibilityLoaded)
    }

    /// A stop's custom actions are what VoiceOver's Actions rotor offers, in the rotor's order, so a test can pin
    /// them. SwiftUI offers the outermost modifier's action first: the last one written is read first.
    @Test func aStopListsItsCustomActionsInRotorOrder() async throws {
        let hosted = await HostedView(
            Text(verbatim: "Photo")
                .accessibilityAction(named: Text(verbatim: "Second")) {}
                .accessibilityAction(named: Text(verbatim: "First")) {}
        )
        defer { hosted.close() }
        let stop = try #require(hosted.stop(labelled: "Photo"), "\(hosted.tree)")
        #expect(stop.customActionNames == ["First", "Second"])
    }
}
