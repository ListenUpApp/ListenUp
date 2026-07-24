import Testing
@testable import ListenUp

@Suite("Register admin badge")
struct AuthAdminBadgeTests {
    /// No first-run signal → generic create-account, no admin badge.
    @Test func badgeHiddenWithoutFirstRunSignal() {
        #expect(RegisterView.showsAdminBadge(isFirstRun: false) == false)
    }

    /// First-run (creating the server's first/admin account) → show the badge.
    @Test func badgeShownOnFirstRun() {
        #expect(RegisterView.showsAdminBadge(isFirstRun: true) == true)
    }
}
