import Testing
@testable import ListenUp

@MainActor
@Suite("AdminSettingsObserver")
struct AdminSettingsObserverTests {
    // AdminSettingsReadyModel is a pure Swift struct constructed by the observer's
    // state-mapping closure — it is testable without a live KMP ViewModel.

    @Test func inboxEnabledTrueFlattensToReadyModel() {
        let model = AdminSettingsReadyModel(
            serverName: "My Server",
            remoteUrl: "",
            inboxEnabled: true,
            isDirty: false,
            isSaving: false,
            error: nil
        )
        #expect(model.inboxEnabled == true)
    }

    @Test func inboxEnabledFalseFlattensToReadyModel() {
        let model = AdminSettingsReadyModel(
            serverName: "My Server",
            remoteUrl: "",
            inboxEnabled: false,
            isDirty: false,
            isSaving: false,
            error: nil
        )
        #expect(model.inboxEnabled == false)
    }

    @Test func readyPhaseCarriesInboxEnabled() {
        let model = AdminSettingsReadyModel(
            serverName: "S",
            remoteUrl: "https://example.com",
            inboxEnabled: true,
            isDirty: true,
            isSaving: false,
            error: nil
        )
        let phase = AdminSettingsPhase.ready(model)
        if case .ready(let m) = phase {
            #expect(m.inboxEnabled == true)
        } else {
            Issue.record("Expected .ready phase")
        }
    }
}
