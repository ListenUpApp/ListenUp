import Testing
import Shared
@testable import ListenUp

/// The mappings `OrganizeSettingsObserver` performs at its boundary: the shared organizer state into
/// native values the Form renders, and the one-shot events into what iOS does with them. Mirrors
/// `AdminBackupObserversTests`.
@Suite("Organize settings phase mapping")
struct OrganizePhaseTests {
    private static func ready(
        preset: OrganizePreset = .authorSeriesTitle,
        isWorking: Bool = false,
        preview: OrganizePreviewDto? = nil,
        run: OrganizeRunProgress? = nil
    ) -> OrganizeSettingsUiStateReady {
        OrganizeSettingsUiStateReady(
            settings: OrganizeSettingsDto(preset: preset, seriesPrefix: .bracketN, authorForm: .lastFirst),
            isWorking: isWorking,
            preview: preview,
            run: run,
            error: nil
        )
    }

    private static func readyModel(_ state: OrganizeSettingsUiStateReady) -> OrganizeReadyModel? {
        guard case .ready(let model) = OrganizeSettingsObserver.phase(from: state) else { return nil }
        return model
    }

    @Test func loadingMapsToLoading() {
        #expect(OrganizeSettingsObserver.phase(from: OrganizeSettingsUiStateLoading.shared) == .loading)
    }

    @Test func aFailedLoadCarriesItsMessage() {
        let appError = ServerConnectErrorInvalidUrl(correlationId: nil, debugInfo: nil, reason: "bad url")
        #expect(
            OrganizeSettingsObserver.phase(from: OrganizeSettingsUiStateError(error: appError))
                == .error(message: appError.message)
        )
    }

    @Test func readyCarriesTheEditBuffer() {
        let model = Self.readyModel(Self.ready(isWorking: true))
        #expect(model?.preset == .authorSeriesTitle)
        #expect(model?.seriesPrefix == .bracketN)
        #expect(model?.authorForm == .lastFirst)
        #expect(model?.isWorking == true)
        #expect(model?.preview == nil)
        #expect(model?.run == nil)
    }

    /// The same rule Android applies: a series number only matters when there is a series folder,
    /// and an author style only when there is an author folder.
    @Test func eachStyleIsOfferedOnlyWhereItsFolderExists() {
        let series = Self.readyModel(Self.ready(preset: .authorSeriesTitle))
        #expect(series?.showsSeriesPrefix == true)
        #expect(series?.showsAuthorForm == true)

        let author = Self.readyModel(Self.ready(preset: .authorTitle))
        #expect(author?.showsSeriesPrefix == false)
        #expect(author?.showsAuthorForm == true)

        let flat = Self.readyModel(Self.ready(preset: .flatTitle))
        #expect(flat?.showsSeriesPrefix == false)
        #expect(flat?.showsAuthorForm == false)
    }

    @Test func theStructureChoicesComeInAndroidsOrder() {
        #expect(OrganizeReadyModel.presets == [.authorSeriesTitle, .authorTitle, .flatTitle])
        #expect(OrganizeReadyModel.seriesPrefixes == [.bookNDash, .nDash, .bracketN, .none])
        #expect(OrganizeReadyModel.authorForms == [.firstLast, .lastFirst])
    }
}

@Suite("Organize preview mapping")
struct OrganizePreviewModelTests {
    private static func entry(
        _ id: String,
        from: String = "/lib/Old/Book",
        to: String = "/lib/New/Book",
        renamedFrom: String? = nil,
        renamedTo: String? = nil,
        collision: Bool = false
    ) -> OrganizePreviewEntryDto {
        OrganizePreviewEntryDto(
            bookId: id, fromPath: from, toPath: to, collisionResolved: collision,
            renamedFrom: renamedFrom, renamedTo: renamedTo
        )
    }

    private static func preview(
        books: Int32, files: Int32 = 12, collisions: Int32 = 1, renames: Int32 = 0,
        entries: [OrganizePreviewEntryDto]
    ) -> OrganizePreviewModel {
        OrganizePreviewModel.from(OrganizePreviewDto(
            bookCount: books, fileCount: files, collisionCount: collisions, entries: entries,
            truncated: false, renamedInPlaceCount: renames
        ))
    }

    @Test func aPlanOfMovesLeadsWithTheMoves() {
        let model = Self.preview(books: 2, entries: [Self.entry("a"), Self.entry("b")])
        #expect(model.movesSummary != nil)
        #expect(model.renamesSummary == nil)
    }

    /// A plan of nothing but in-place renames counts zero folders. Leading with "moves 0 files
    /// across 0 folders" would report real work as a no-op, so the renames line stands alone.
    @Test func aPlanOfOnlyRenamesDoesNotClaimToMoveNothing() {
        let model = Self.preview(
            books: 0, files: 0, collisions: 0, renames: 3,
            entries: [Self.entry("a", renamedFrom: "a.m4b", renamedTo: "Book.m4b")]
        )
        #expect(model.movesSummary == nil)
        #expect(model.renamesSummary != nil)
    }

    /// An in-place rename's folder does not change, so its row shows the two filenames; a move shows
    /// the folder it leaves and the path it lands on.
    @Test func aRenameRowShowsFilenamesAndAMoveRowShowsTheFolder() {
        let model = Self.preview(books: 1, renames: 1, entries: [
            Self.entry("move", from: "/lib/Loose/Mistborn", to: "/lib/Sanderson/Mistborn", collision: true),
            Self.entry("rename", renamedFrom: "track.m4b", renamedTo: "Elantris.m4b")
        ])
        #expect(model.rows.map(\.id) == ["move", "rename"])
        #expect(model.rows[0].before == "Mistborn")
        #expect(model.rows[0].after == "/lib/Sanderson/Mistborn")
        #expect(model.rows[1].before == "track.m4b")
        #expect(model.rows[1].after == "Elantris.m4b")
    }

    /// A sample, not the whole plan: the first eight rows, then "…and N more" for every planned book
    /// not listed.
    @Test func showsEightRowsAndCountsTheRest() {
        let entries = (0..<10).map { Self.entry("b\($0)") }
        let model = Self.preview(books: 15, renames: 2, entries: entries)
        #expect(model.rows.count == 8)
        #expect(model.moreCount == 9)
    }

    @Test func aFullyListedPlanHasNothingMore() {
        let model = Self.preview(books: 2, entries: [Self.entry("a"), Self.entry("b")])
        #expect(model.moreCount == 0)
    }
}

@Suite("Organize run mapping")
struct OrganizeRunModelTests {
    private static func run(
        completed: Int32, total: Int32, moved: Int32 = 0, failed: Int32 = 0, terminal: Bool = false
    ) -> OrganizeRunModel {
        OrganizeRunModel.from(OrganizeRunProgress(
            completed: completed, total: total, movedBooks: moved, failedBooks: failed, terminal: terminal
        ))
    }

    @Test func aRunInFlightReportsItsFraction() {
        let model = Self.run(completed: 3, total: 12)
        #expect(!model.isFinished)
        #expect(model.fraction == 0.25)
    }

    /// Before the server has said how many books there are, the bar sits at zero rather than
    /// dividing by nothing.
    @Test func aRunWithNoTotalYetIsAtZero() {
        #expect(Self.run(completed: 0, total: 0).fraction == 0)
    }

    @Test func aFinishedRunWithFailuresOffersResume() {
        let model = Self.run(completed: 5, total: 5, moved: 4, failed: 1, terminal: true)
        #expect(model.isFinished)
        #expect(model.hasFailures)
        #expect(model.movedBooks == 4)
        #expect(model.failedBooks == 1)
    }

    @Test func aCleanFinishOffersNoResume() {
        #expect(!Self.run(completed: 5, total: 5, moved: 5, terminal: true).hasFailures)
    }
}

@Suite("Organize events")
struct OrganizeEffectTests {
    /// Saving moves nothing, so the confirmation has to say so — nothing on screen changes.
    @Test func savedRulesSayTheyWereSaved() {
        let effect = OrganizeSettingsObserver.effect(of: OrganizeSettingsEventRulesSaved.shared)
        #expect(effect == .confirmSaved)
        #expect(effect.message == String(localized: "admin.organize_saved"))
    }

    /// Organize was tapped and nothing needed to move — an answer, not silence.
    @Test func anAlreadyOrganizedLibraryIsSaidSo() {
        let effect = OrganizeSettingsObserver.effect(of: OrganizeSettingsEventAlreadyOrganized.shared)
        #expect(effect == .alreadyOrganized)
        #expect(effect.message == String(localized: "admin.organize_already"))
    }
}
