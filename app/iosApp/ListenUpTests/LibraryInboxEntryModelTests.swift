import Testing
@testable import ListenUp

/// The Library's inbox entry as a value: absent at zero, its title and its one VoiceOver label.
@Suite("Library inbox entry")
struct LibraryInboxEntryModelTests {
    @Test func nothingHeldMeansNoEntry() {
        #expect(LibraryInboxEntryModel.make(count: 0, previewBookIds: []) == nil)
    }

    @Test func titleCountsTheBooks() throws {
        let model = try #require(LibraryInboxEntryModel.make(count: 3, previewBookIds: ["b3", "b2", "b1"]))
        #expect(model.title == "Inbox · 3 new books")
    }

    @Test func oneBookIsOneBook() throws {
        let model = try #require(LibraryInboxEntryModel.make(count: 1, previewBookIds: ["b1"]))
        #expect(model.title == "Inbox · 1 new book")
    }

    @Test func voiceOverHearsOneSentence() throws {
        let model = try #require(LibraryInboxEntryModel.make(count: 3, previewBookIds: []))
        #expect(model.accessibilityLabel == "Inbox, 3 books waiting for review")
    }

    @Test func theFanShowsAtMostThreeCovers() throws {
        let model = try #require(LibraryInboxEntryModel.make(count: 5, previewBookIds: ["a", "b", "c", "d"]))
        #expect(model.previewBookIds == ["a", "b", "c"])
    }
}
