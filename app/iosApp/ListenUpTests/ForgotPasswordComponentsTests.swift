import Testing
@testable import ListenUp

/// The two decisions in the forgot-password visuals that are logic rather than layout: how much
/// of the attempt budget is worth an alarm, and what counts as a usable code.
///
/// Both are pinned here because getting either wrong is silent. An attempts threshold that shouts
/// too early spends the alarm before it means anything; a code rule stricter than the server's
/// rejects input the server would happily accept, from the exact person this flow exists to rescue.
@MainActor
@Suite("Forgot password components")
struct ForgotPasswordComponentsTests {

    // MARK: - Attempt budget

    @Test("a comfortable budget is not worth mentioning")
    func comfortableBudgetStaysQuiet() {
        #expect(ForgotPasswordAttempts.worthMentioning == 4)
        #expect(!isWorthMentioning(5))
        #expect(!isWorthMentioning(4))
    }

    @Test("a shrinking budget is mentioned")
    func shrinkingBudgetIsMentioned() {
        #expect(isWorthMentioning(3))
        #expect(isWorthMentioning(2))
        #expect(isWorthMentioning(1))
    }

    @Test("an unknown budget says nothing")
    func unknownBudgetSaysNothing() {
        #expect(!isWorthMentioning(nil))
    }

    private func isWorthMentioning(_ remaining: Int?) -> Bool {
        guard let remaining else { return false }
        return remaining < ForgotPasswordAttempts.worthMentioning
    }

    // MARK: - Code shape

    /// Regression: this asserted 6 — the comp's OTP default — while the server issues 8
    /// (`YGFD-NBRW`). Six cells silently truncated every real code, so completion was impossible.
    @Test("the code field holds exactly the characters a server reset code has")
    func codeLengthMatchesTheServer() {
        #expect(ForgotPasswordCodeField.length == 8)
    }
}
