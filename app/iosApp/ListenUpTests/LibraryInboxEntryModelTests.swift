import Testing
@testable import ListenUp

/// The Library's inbox entry as a value (spec a6): absent at zero, its one-line title and its one VoiceOver label.
@Suite("Library inbox entry")
struct LibraryInboxEntryModelTests {
    @Test func nothingHeldMeansNoEntry() {
        #expect(LibraryInboxEntryModel.make(count: 0) == nil)
    }

    @Test func titleIsOneLineCountingWhatWaits() throws {
        let model = try #require(LibraryInboxEntryModel.make(count: 3))
        #expect(model.title == "Inbox · 3 waiting")
    }

    @Test func voiceOverHearsOneSentence() throws {
        let model = try #require(LibraryInboxEntryModel.make(count: 3))
        #expect(model.accessibilityLabel == "Inbox, 3 books waiting for review")
    }

    @Test func oneBookIsOneBookToVoiceOver() throws {
        let model = try #require(LibraryInboxEntryModel.make(count: 1))
        #expect(model.accessibilityLabel == "Inbox, 1 book waiting for review")
    }
}
