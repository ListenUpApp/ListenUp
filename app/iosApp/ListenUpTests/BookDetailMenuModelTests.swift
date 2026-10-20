import Testing
@testable import ListenUp

/// Book Detail's More menu on iOS (spec §2.7): the shared rule's groups as menu sections, in HIG words.
@Suite("Book Detail More menu")
struct BookDetailMenuModelTests {
    private let adminGroups: [BookDetailMenuSection] = [
        .init(heading: nil, commands: [.share], isDestructive: false),
        .init(heading: .progress, commands: [.markFinished, .restart, .markNotStarted], isDestructive: false),
        .init(heading: .edit, commands: [.editDetails, .matchDetails, .editChapters], isDestructive: false),
        .init(heading: .admin, commands: [.addToCollection], isDestructive: false),
        .init(heading: nil, commands: [.deleteFromServer], isDestructive: true),
    ]

    @Test func shareLeadsAndDeleteClosesAlone() {
        let sections = BookDetailMenuModel.sections(adminGroups, canShare: true)
        #expect(sections.first?.commands == [.share])
        #expect(sections.last?.commands == [.deleteFromServer])
        #expect(sections.last?.isDestructive == true)
        #expect(sections.filter(\.isDestructive).count == 1)
    }

    @Test func withoutAShareLinkTheShareSectionGoesEntirely() {
        let sections = BookDetailMenuModel.sections(adminGroups, canShare: false)
        #expect(sections.first?.heading == .progress)
        #expect(sections.allSatisfy { !$0.commands.isEmpty })
    }

    @Test func theWordsAreTitleCase() {
        #expect(BookDetailMenuCommand.markFinished.title == "Mark as Finished…")
        #expect(BookDetailMenuCommand.restart.title == "Start Over…")
        #expect(BookDetailMenuCommand.markNotStarted.title == "Mark as Not Started…")
        #expect(BookDetailMenuCommand.editDetails.title == "Edit Details")
        #expect(BookDetailMenuCommand.matchDetails.title == "Match Details…")
        #expect(BookDetailMenuCommand.editChapters.title == "Edit Chapters")
        #expect(BookDetailMenuCommand.addToCollection.title == "Add to Collection…")
        #expect(BookDetailMenuCommand.deleteFromServer.title == "Delete from Server…")
        #expect(BookDetailMenuCommand.share.title == "Share")
        #expect(BookDetailMenuHeadingKind.progress.title == "Progress")
    }

    @Test func onlyDeleteCarriesTheDestructiveRole() {
        #expect(BookDetailMenuCommand.deleteFromServer.isDestructive)
        #expect(!BookDetailMenuCommand.markNotStarted.isDestructive)
    }

    @Test func theProgressCommandsWaitWhileAWriteIsInFlight() {
        #expect(BookDetailMenuCommand.restart.waitsOnProgress)
        #expect(!BookDetailMenuCommand.share.waitsOnProgress)
    }
}
