import Testing
@testable import ListenUp

@Suite("SetupValidation")
struct SetupValidationTests {
    @Test func passwordsMatchWhenEqual() {
        #expect(SetupValidation.passwordMismatch(password: "abc12345", confirm: "abc12345") == false)
    }
    @Test func passwordsMismatchWhenDifferent() {
        #expect(SetupValidation.passwordMismatch(password: "abc12345", confirm: "different") == true)
    }
    @Test func emptyConfirmIsNotFlaggedUntilTyped() {
        #expect(SetupValidation.passwordMismatch(password: "abc12345", confirm: "") == false)
    }
    @Test func fieldForSetupFieldMapsToKey() {
        #expect(SetupValidation.errorField(for: .email) == .email)
        #expect(SetupValidation.errorField(for: .passwordConfirm) == .passwordConfirm)
    }
}
