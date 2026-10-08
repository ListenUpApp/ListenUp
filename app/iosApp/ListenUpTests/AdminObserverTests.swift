import Testing
import Shared
@testable import ListenUp

/// Pure-mapping coverage for the admin observers' seams: the access-label badge the
/// users list renders, the create-invite access-level → wire-role projection, and the
/// failure-type → UI routing (which field to highlight, which banner to show).
struct AdminObserverTests {
    // MARK: - Access labels

    @Test func ownersAndAdminsGetTheElevatedBadge() {
        #expect(AdminRoleFormat.isElevated(.owner) == true)
        #expect(AdminRoleFormat.isElevated(.admin) == true)
        #expect(AdminRoleFormat.isElevated(.contributor) == false)
        #expect(AdminRoleFormat.isElevated(.librarian) == false)
        #expect(AdminRoleFormat.isElevated(.member) == false)
    }

    @Test func capitalizedUppercasesOnlyFirstCharacter() {
        #expect(AdminRoleFormat.capitalized("member") == "Member")
        #expect(AdminRoleFormat.capitalized("READER") == "READER")
        #expect(AdminRoleFormat.capitalized("").isEmpty)
    }

    // MARK: - Access level ↔ wire role

    @Test func accessLevelMapsToWireRole() {
        #expect(InviteRole.member.wireValue == "member")
        #expect(InviteRole.admin.wireValue == "admin")
    }

    @Test func bothAccessLevelsAreOffered() {
        #expect(InviteRole.allCases == [.member, .admin])
    }

    // MARK: - Validation field routing

    @Test func validationErrorRoutesToEmailField() {
        let emailFailure = InviteFailure(from: CreateInviteErrorTypeValidationError(field: .email))
        #expect(emailFailure == .validation(.email))
    }

    @Test func emailInUseMapsToItsCase() {
        #expect(InviteFailure(from: CreateInviteErrorTypeEmailInUse.shared) == .emailInUse)
    }

    @Test func networkAndServerCarryTheirDetail() {
        let network = InviteFailure(from: CreateInviteErrorTypeNetworkError(detail: "offline"))
        #expect(network == .network(detail: "offline"))
        let server = InviteFailure(from: CreateInviteErrorTypeServerError(detail: "boom"))
        #expect(server == .server(detail: "boom"))
    }

    // MARK: - Phase projection from status

    @Test func submittingStatusProjectsToSubmittingPhase() {
        #expect(CreateInviteObserver.phase(from: CreateInviteStatusSubmitting.shared) == .submitting)
        #expect(CreateInviteObserver.phase(from: CreateInviteStatusIdle.shared) == .idle)
    }

    @Test func successStatusCarriesTheInvite() {
        let invite = makeInvite(name: "Sarah", url: "host/join/abc")
        let phase = CreateInviteObserver.phase(from: CreateInviteStatusSuccess(invite: invite))
        #expect(phase.createdInvite == CreatedInviteModel(name: "Sarah", url: "host/join/abc"))
    }

    @Test func validationPhaseExposesItsFieldButNoBanner() {
        let status = CreateInviteStatusError(type: CreateInviteErrorTypeValidationError(field: .email))
        let phase = CreateInviteObserver.phase(from: status)
        #expect(phase.validationField == .email)
        #expect(phase.bannerMessage == nil)
    }

    @Test func nonValidationFailureExposesABannerButNoField() {
        let status = CreateInviteStatusError(type: CreateInviteErrorTypeEmailInUse.shared)
        let phase = CreateInviteObserver.phase(from: status)
        #expect(phase.validationField == nil)
        #expect(phase.bannerMessage != nil)
    }

    // MARK: - ISO date parsing

    @Test func parsesPlainIsoTimestamp() {
        #expect(ISO8601DateParser.date(from: "2026-06-14T12:00:00Z") != nil)
    }

    @Test func parsesFractionalSecondsTimestamp() {
        #expect(ISO8601DateParser.date(from: "2026-06-14T12:00:00.123Z") != nil)
    }

    @Test func rejectsGarbageTimestamp() {
        #expect(ISO8601DateParser.date(from: "not-a-date") == nil)
    }

    // MARK: - Helpers

    private func makeInvite(name: String, url: String) -> InviteInfo {
        InviteInfo(
            id: "i1",
            code: "abc",
            name: name,
            email: "x@example.com",
            role: "member",
            expiresAt: "2026-06-14T12:00:00Z",
            claimedAt: nil,
            url: url,
            createdAt: "2026-06-01T12:00:00Z"
        )
    }
}
