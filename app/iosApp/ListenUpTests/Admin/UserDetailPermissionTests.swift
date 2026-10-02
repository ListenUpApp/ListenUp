import Testing
@testable import ListenUp

/// The admin user-detail screen offers exactly the permissions the server still enforces. "Can share"
/// gated nothing once only admins could write collections and was removed; a toggle that changes
/// nothing is a lie on an admin screen, so this pins that it does not come back.
struct UserDetailPermissionTests {
    @Test func canEditIsTheOnlyPermissionOffered() {
        #expect(UserDetailPermission.allCases == [.canEdit])
    }

    @Test func noPermissionToggleIsCanShare() {
        let titles = UserDetailPermission.allCases.map(\.title)
        #expect(!titles.contains("Can share"))
        #expect(!titles.contains("admin.can_share"))
    }
}
