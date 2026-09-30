import Testing
@testable import ListenUp

/// Return walks each form's fields in reading order and stops where a run ends.
@Suite("Form focus chains")
struct FormFocusChainTests {
    /// Every field in `chain` hands on to the one after it, and the last ends the run.
    private func expectRun<Field: FormFocusChain>(_ chain: [Field]) {
        for (field, following) in zip(chain, chain.dropFirst()) {
            #expect(field.next == following, "\(field) should move to \(following)")
        }
        if let last = chain.last { #expect(last.next == nil, "\(last) should end its run") }
    }

    @Test func loginPasswordEndsTheRun() {
        expectRun([LoginFocusField.email, .password])
    }

    @Test func registerWalksNamesEmailThenPasswords() {
        expectRun([RegisterFocusField.firstName, .lastName, .email, .password, .confirmPassword])
    }

    @Test func setupWalksNamesEmailThenPasswords() {
        expectRun([SetupFocusField.firstName, .lastName, .email, .password, .confirmPassword])
    }

    @Test func claimInviteEndsOnThePassword() {
        expectRun([ClaimInviteFocusField.firstName, .lastName, .password])
    }

    @Test func editProfileKeepsNamesAndPasswordsApart() {
        expectRun([EditProfileFocusField.firstName, .lastName])
        expectRun([EditProfileFocusField.currentPassword, .newPassword, .confirmPassword])
        #expect(EditProfileFocusField.tagline.next == nil)
    }

    @Test func bookEditHasTwoRunsAndSkipsTheMultilineDescription() {
        expectRun([BookEditFocusField.title, .subtitle, .sortTitle])
        expectRun([BookEditFocusField.publisher, .year])
        expectRun([BookEditFocusField.isbn, .asin])
    }

    @MainActor
    @Test func advanceSubmitsOnlyAtTheEndOfARun() {
        var submitted = 0
        var focus: LoginFocusField? = .email
        FormFocus.advance(from: LoginFocusField.email, moveTo: { focus = $0 }, onEnd: { submitted += 1 })
        #expect(focus == .password)
        #expect(submitted == 0)
        FormFocus.advance(from: LoginFocusField.password, moveTo: { focus = $0 }, onEnd: { submitted += 1 })
        #expect(focus == .password)
        #expect(submitted == 1)
    }
}
