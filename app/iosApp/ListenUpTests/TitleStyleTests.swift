import Testing
@testable import ListenUp

// iOS buttons take title case (HIG, Writing); the shared strings are sentence case. The derivation
// capitalizes the words that matter, leaves short joining words and existing capitals alone, and
// touches English only.

@Suite("Title-style button labels")
struct TitleStyleTests {
    @Test func everyMajorWordIsCapitalized() {
        #expect(String.titleStyled("Sync now", languageCode: "en") == "Sync Now")
        #expect(String.titleStyled("Try again", languageCode: "en") == "Try Again")
        #expect(String.titleStyled("Change match", languageCode: "en") == "Change Match")
        #expect(String.titleStyled("Remove match", languageCode: "en") == "Remove Match")
    }

    @Test func shortJoiningWordsStayLowercaseExceptAtTheEdges() {
        #expect(String.titleStyled("Find on Hardcover", languageCode: "en") == "Find on Hardcover")
        #expect(String.titleStyled("Mark as read", languageCode: "en") == "Mark as Read")
        #expect(String.titleStyled("Sign in", languageCode: "en") == "Sign In")
    }

    @Test func wordsWithCapitalsAreLeftAsWritten() {
        #expect(String.titleStyled("Open ListenUp on iPhone", languageCode: "en") == "Open ListenUp on iPhone")
    }

    @Test func otherLanguagesAreUntouched() {
        #expect(String.titleStyled("Synchroniser maintenant", languageCode: "fr") == "Synchroniser maintenant")
    }
}
