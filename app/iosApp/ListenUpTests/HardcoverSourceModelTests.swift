import Testing
import Shared
@testable import ListenUp

/// Admin → Hardcover on iOS (#1542): the shared status flattened to what the section shows — never the token.
@Suite("HardcoverSourceModel")
struct HardcoverSourceModelTests {
    @Test func noTokenAndNothingToReadWith() {
        let model = HardcoverSourceModel.from(
            status(token: HardcoverApiTokenStatusNotSet.shared, unavailable: .noConnection),
            save: HardcoverTokenSaveIdle.shared
        )
        #expect(model.token == .notSet)
        #expect(model.savedLine == nil)
        #expect(model.metadataUnavailable == true)
        #expect(model.metadataSubtitle == "Add an API token or connect a Hardcover account to enable")
    }

    @Test func aSavedTokenIsDescribedByItsOwnerOnly() {
        let model = HardcoverSourceModel.from(
            status(token: HardcoverApiTokenStatusSaved(username: "simon", setAt: 1)),
            save: HardcoverTokenSaveIdle.shared
        )
        #expect(model.token == .saved(username: "simon"))
        #expect(model.savedLine == "Set · belongs to @simon")
        #expect(
            model.metadataSubtitle == "Offer Hardcover's moods, genres, series and descriptions when you match a book"
        )
    }

    @Test func aRejectedTokenAsksToBeReplaced() {
        let model = HardcoverSourceModel.from(
            status(token: HardcoverApiTokenStatusRejected(username: "simon")),
            save: HardcoverTokenSaveIdle.shared
        )
        #expect(model.token == .rejected(username: "simon"))
        #expect(model.savedLine == nil)
    }

    @Test func checkingIsBusyAndARefusalCarriesItsMessage() {
        let busy = HardcoverSourceModel.from(
            status(token: HardcoverApiTokenStatusNotSet.shared),
            save: HardcoverTokenSaveBusy.shared
        )
        #expect(busy.isBusy == true)
        #expect(busy.refusal == nil)

        let refused = HardcoverSourceModel.from(
            status(token: HardcoverApiTokenStatusNotSet.shared),
            save: HardcoverTokenSaveRefused(error: HardcoverErrorTokenRejected(correlationId: nil, debugInfo: nil))
        )
        #expect(refused.isBusy == false)
        #expect(refused.refusal == "Hardcover didn't accept that token. Check it and try again.")
    }

    @Test func theMetadataSwitchFollowsTheSetting() {
        let off = HardcoverSourceModel.from(
            status(token: HardcoverApiTokenStatusNotSet.shared, enabled: false),
            save: HardcoverTokenSaveIdle.shared
        )
        #expect(off.metadataEnabled == false)
    }

    /// Swift Export carries no Kotlin defaults, so every field is named.
    private func status(
        token: HardcoverApiTokenStatus,
        enabled: Bool = true,
        unavailable: RatingSourceUnavailable? = nil
    ) -> HardcoverSourceStatus {
        HardcoverSourceStatus(apiToken: token, metadataEnabled: enabled, metadataUnavailable: unavailable)
    }
}
