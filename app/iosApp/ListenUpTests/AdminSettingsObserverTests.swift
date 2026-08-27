import Testing
import Shared
@testable import ListenUp

@Suite("AdminSettingsObserver")
struct AdminSettingsObserverTests {
    // Tests exercise `AdminSettingsReadyModel.from(_:)` — the real KMP-Ready → Swift mapping
    // closure — so regressions that drop a field mapping are caught here, not just at runtime.

    @Test func holdNewBooksForReviewTrueMapsToReadyModel() {
        let ready = AdminSettingsUiStateReady(
            serverName: "My Server",
            remoteUrl: "https://example.com",
            holdNewBooksForReview: true,
            pushNotificationsEnabled: true,
            isDirty: false,
            isSaving: false,
            error: nil
        )
        let model = AdminSettingsReadyModel.from(ready)
        #expect(model.holdNewBooksForReview == true)
        #expect(model.pushNotificationsEnabled == true)
        #expect(model.serverName == "My Server")
        #expect(model.remoteUrl == "https://example.com")
        #expect(model.error == nil)
    }

    @Test func holdNewBooksForReviewFalseMapsToReadyModel() {
        let ready = AdminSettingsUiStateReady(
            serverName: "Inbox Off",
            remoteUrl: "",
            holdNewBooksForReview: false,
            pushNotificationsEnabled: false,
            isDirty: true,
            isSaving: false,
            error: nil
        )
        let model = AdminSettingsReadyModel.from(ready)
        #expect(model.holdNewBooksForReview == false)
        #expect(model.pushNotificationsEnabled == false)
        #expect(model.serverName == "Inbox Off")
        #expect(model.isDirty == true)
    }

    @Test func dirtyAndSavingFlagsMapped() {
        let ready = AdminSettingsUiStateReady(
            serverName: "S",
            remoteUrl: "https://example.com",
            holdNewBooksForReview: true,
            pushNotificationsEnabled: true,
            isDirty: true,
            isSaving: true,
            error: nil
        )
        let model = AdminSettingsReadyModel.from(ready)
        #expect(model.isDirty == true)
        #expect(model.isSaving == true)
        #expect(model.holdNewBooksForReview == true)
    }
}
