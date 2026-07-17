import Foundation
import Shared

/// Observes `ImportFlowViewModel` — the linear ABS-import wizard — and flattens its sealed
/// `ImportFlowUiState` (`Idle` / `Uploading` / `Analyzing` / `Review` / `Applying` / `Done` /
/// `Error`) into a SwiftUI-native `ImportFlowPhase` the wizard sheet binds to.
///
/// The mockup's six wizard screens map onto these phases: Intro = `.idle`, Uploading =
/// `.uploading`, Analyzing = `.analyzing`, Review Users = `.review`, Apply = `.applying`,
/// Import Complete = `.done`. A failure surfaces as `.error` with the typed `AppError`'s
/// localized message.
///
/// The user-review rows are flattened Kotlin-side via `ImportFlowSwiftBridge` — the VM holds the
/// ABS/ListenUp ids in value classes nested inside its `Map`/`Set` state (awkward to read across the
/// Swift Export boundary), so the bridge reads them where the Kotlin types are in scope and hands
/// back plain strings. Progress maths
/// (the ring fraction, the counters) live in pure, testable statics.
///
/// Thin over `FlowBridge`, mirroring `AdminObserver`.
@Observable
@MainActor
final class ImportFlowObserver {
    // MARK: - State

    private(set) var phase: ImportFlowPhase = .idle

    // MARK: - Dependencies

    private let viewModel: ImportFlowViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: ImportFlowViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.uiState) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    /// Begin the wizard by uploading the picked backup bytes. The Swift caller reads the
    /// security-scoped file into a `FileSource`; the VM streams it and drives analyze.
    func start(fileSource: FileSource) {
        viewModel.start(fileSource: fileSource)
    }

    func assignUser(absUserId: String, listenUpUserId: String) {
        ImportFlowSwiftBridge.shared.assignUser(
            viewModel: viewModel,
            absUserId: absUserId,
            listenUpUserId: listenUpUserId
        )
    }

    func skipUser(absUserId: String) {
        ImportFlowSwiftBridge.shared.skipUser(viewModel: viewModel, absUserId: absUserId)
    }

    func openBookSearch(absItemId: String) {
        ImportFlowSwiftBridge.shared.openBookSearch(viewModel: viewModel, absItemId: absItemId)
    }

    func closeBookSearch() {
        ImportFlowSwiftBridge.shared.closeBookSearch(viewModel: viewModel)
    }

    func updateBookSearchQuery(_ query: String) {
        ImportFlowSwiftBridge.shared.updateBookSearchQuery(viewModel: viewModel, query: query)
    }

    func selectBook(absItemId: String, bookId: String) {
        ImportFlowSwiftBridge.shared.selectBook(viewModel: viewModel, absItemId: absItemId, bookId: bookId)
    }

    func skipBook(absItemId: String) {
        ImportFlowSwiftBridge.shared.skipBook(viewModel: viewModel, absItemId: absItemId)
    }

    func confirmAndApply() {
        viewModel.confirmAndApply()
    }

    func reset() {
        viewModel.reset()
    }

    // MARK: - State mapping

    private func apply(_ state: ImportFlowUiState) {
        switch onEnum(of: state) {
        case .idle:
            phase = .idle
        case .uploading(let uploading):
            phase = .uploading(filename: uploading.filename)
        case .analyzing(let analyzing):
            phase = .analyzing(ImportProgressModel(
                done: Int(analyzing.done),
                total: Int(analyzing.total),
                currentItem: analyzing.currentItem,
                usersMatched: Int(analyzing.usersMatched),
                booksMatched: Int(analyzing.booksMatched)
            ))
        case .review(let review):
            // The boxed value-class state is flattened Kotlin-side; the picker users bridge
            // cleanly (AdminUserInfo has String ids), so map those here.
            let snapshot = ImportFlowSwiftBridge.shared.reviewSnapshot(state: state)
            let pickerUsers = review.listenupUsers.map(ImportPickerUser.init(from:))
            phase = .review(ImportReviewModel(snapshot: snapshot, pickerUsers: pickerUsers))
        case .applying(let applying):
            phase = .applying(ImportProgressModel(
                done: Int(applying.done),
                total: Int(applying.total),
                currentItem: applying.currentItem,
                sessionsWritten: Int(applying.sessionsWritten)
            ))
        case .done(let done):
            phase = .done(ImportDoneModel(from: done.result))
        case .error(let error):
            phase = .error(message: error.error.message)
        case .unknown:
            Log.error("Unexpected ImportFlowUiState case")
            phase = .error(message: String(localized: "common.something_went_wrong"))
        }
    }
}

// MARK: - Phase

/// Flattened wizard state for a SwiftUI `switch`.
enum ImportFlowPhase: Equatable {
    case idle
    case uploading(filename: String)
    case analyzing(ImportProgressModel)
    case review(ImportReviewModel)
    case applying(ImportProgressModel)
    case done(ImportDoneModel)
    case error(message: String)

    /// Whether the wizard is in a terminal state the user can dismiss/reset from.
    var isTerminal: Bool {
        switch self {
        case .done, .error: return true
        default: return false
        }
    }
}

// MARK: - Progress model

/// A live progress snapshot for the Analyzing / Applying phases. Carries the raw counters plus
/// the derived ring `fraction` so the ring view stays declarative.
struct ImportProgressModel: Equatable {
    let done: Int
    let total: Int
    let currentItem: String?
    let usersMatched: Int
    let booksMatched: Int
    let sessionsWritten: Int

    init(done: Int, total: Int, currentItem: String?, usersMatched: Int, booksMatched: Int) {
        self.done = done
        self.total = total
        self.currentItem = currentItem
        self.usersMatched = usersMatched
        self.booksMatched = booksMatched
        self.sessionsWritten = 0
    }

    init(done: Int, total: Int, currentItem: String?, sessionsWritten: Int) {
        self.done = done
        self.total = total
        self.currentItem = currentItem
        self.usersMatched = 0
        self.booksMatched = 0
        self.sessionsWritten = sessionsWritten
    }

    /// The ring fill 0...1, or nil when no total is known yet (indeterminate spinner).
    var fraction: Double? { ImportProgressMath.fraction(done: done, total: total) }

    /// `"343 / 2,751"` counter, or nil before totals arrive.
    var counterLabel: String? { ImportProgressMath.counterLabel(done: done, total: total) }

    /// The integer percent for the ring center, or nil before totals arrive.
    var percent: Int? {
        guard let fraction else { return nil }
        return Int((fraction * 100).rounded())
    }
}

// MARK: - Review model

/// The fully-flattened Review snapshot the wizard renders: one row per ABS user (with its
/// resolved state) plus the picker's available ListenUp users and the running counts.
struct ImportReviewModel: Equatable {
    let users: [ImportUserRowModel]
    /// Ambiguous / unmatched book items the admin resolves (search-assign or skip).
    let books: [ImportBookRowModel]
    /// The open book-search panel, or nil when none is open.
    let bookSearch: ImportBookSearchModel?
    let listenupUsers: [ImportPickerUser]
    let autoMatchedCount: Int
    let booksMatchedCount: Int
    let ambiguousCount: Int
    let unmatchedCount: Int
    let importableSessionCount: Int

    /// Number of ABS users still needing a decision (neither assigned nor skipped).
    var unresolvedCount: Int { users.filter { $0.resolution == .needsReview }.count }
    var matchedCount: Int { users.filter { $0.resolution != .needsReview }.count }
    /// Number of book items still needing a decision.
    var unresolvedBookCount: Int { books.filter { $0.resolution == .needsReview }.count }

    /// Build from the Kotlin-flattened snapshot + the picker users (for resolving display names).
    init(snapshot: ImportReviewSnapshot?, pickerUsers: [ImportPickerUser]) {
        let userSnapshots = snapshot?.users ?? []
        self.users = userSnapshots.map { ImportUserRowModel(snapshot: $0, pickerUsers: pickerUsers) }
        let bookSnapshots = snapshot?.books ?? []
        self.books = bookSnapshots.map { ImportBookRowModel(snapshot: $0) }
        self.bookSearch = snapshot?.bookSearch.map { ImportBookSearchModel(snapshot: $0) }
        self.listenupUsers = pickerUsers
        self.autoMatchedCount = Int(snapshot?.autoMatchedCount ?? 0)
        self.booksMatchedCount = Int(snapshot?.booksMatchedCount ?? 0)
        self.ambiguousCount = Int(snapshot?.ambiguousCount ?? 0)
        self.unmatchedCount = Int(snapshot?.unmatchedCount ?? 0)
        self.importableSessionCount = Int(snapshot?.importableSessionCount ?? 0)
    }

    /// Memberwise init for tests / previews.
    init(
        users: [ImportUserRowModel],
        books: [ImportBookRowModel] = [],
        bookSearch: ImportBookSearchModel? = nil,
        listenupUsers: [ImportPickerUser],
        autoMatchedCount: Int = 0,
        booksMatchedCount: Int,
        ambiguousCount: Int,
        unmatchedCount: Int,
        importableSessionCount: Int
    ) {
        self.users = users
        self.books = books
        self.bookSearch = bookSearch
        self.listenupUsers = listenupUsers
        self.autoMatchedCount = autoMatchedCount
        self.booksMatchedCount = booksMatchedCount
        self.ambiguousCount = ambiguousCount
        self.unmatchedCount = unmatchedCount
        self.importableSessionCount = importableSessionCount
    }
}

// MARK: - Book row model

/// The resolution state of one ABS book item in Review: assigned to a ListenUp book, explicitly
/// skipped, or still needing the admin's decision.
enum ImportBookResolution: Equatable {
    case assigned(bookId: String)
    case skipped
    case needsReview
}

/// One ambiguous/unmatched ABS book item in Review, mapped from the Kotlin-flattened snapshot.
/// `isUnmatched` selects the tier label; `resolution` reflects the admin's running choice.
struct ImportBookRowModel: Identifiable, Equatable {
    let absItemId: String
    let title: String
    let asin: String?
    let isbn: String?
    let isUnmatched: Bool
    let resolution: ImportBookResolution

    var id: String { absItemId }

    /// "ASIN: … · ISBN: …" identifier line, or empty when neither is present.
    var identifiers: String {
        [asin.map { "ASIN: \($0)" }, isbn.map { "ISBN: \($0)" }]
            .compactMap { $0 }
            .joined(separator: " · ")
    }

    init(snapshot: ImportReviewBookSnapshot) {
        self.absItemId = snapshot.absItemId
        self.title = snapshot.title
        self.asin = snapshot.asin
        self.isbn = snapshot.isbn
        self.isUnmatched = snapshot.isUnmatched
        self.resolution = Self.resolve(snapshot)
    }

    /// Memberwise init for tests / previews.
    init(
        absItemId: String,
        title: String,
        asin: String?,
        isbn: String?,
        isUnmatched: Bool,
        resolution: ImportBookResolution
    ) {
        self.absItemId = absItemId
        self.title = title
        self.asin = asin
        self.isbn = isbn
        self.isUnmatched = isUnmatched
        self.resolution = resolution
    }

    /// Pure: classify one item's resolution from its snapshot's `RESOLUTION_*` marker.
    static func resolve(_ snapshot: ImportReviewBookSnapshot) -> ImportBookResolution {
        switch snapshot.resolution {
        case ImportUserRowModel.Marker.assigned:
            return .assigned(bookId: snapshot.assignedBookId ?? "")
        case ImportUserRowModel.Marker.skipped:
            return .skipped
        default:
            return .needsReview
        }
    }
}

// MARK: - Book search

/// One result row in the book-search panel.
struct ImportBookSearchHit: Identifiable, Equatable {
    let bookId: String
    let title: String
    let author: String

    var id: String { bookId }
}

/// The open book-search panel for one ABS item, mapped from the Kotlin-flattened snapshot.
struct ImportBookSearchModel: Equatable {
    let absItemId: String
    let query: String
    let isSearching: Bool
    let results: [ImportBookSearchHit]

    init(snapshot: ImportBookSearchSnapshot) {
        self.absItemId = snapshot.absItemId
        self.query = snapshot.query
        self.isSearching = snapshot.isSearching
        self.results = snapshot.results.map {
            ImportBookSearchHit(bookId: $0.bookId, title: $0.title, author: $0.author)
        }
    }

    /// Memberwise init for tests / previews.
    init(absItemId: String, query: String, isSearching: Bool, results: [ImportBookSearchHit]) {
        self.absItemId = absItemId
        self.query = query
        self.isSearching = isSearching
        self.results = results
    }
}

// MARK: - User row model

/// The resolution state of one ABS user in Review: explicitly assigned, explicitly skipped, or
/// still needing the admin's decision (the default).
enum ImportUserResolution: Equatable {
    case assigned(toName: String)
    case skipped
    case needsReview
}

/// One ABS user row in Review. `suggestedName`/`suggestedUserId` carry the analysis's one-tap
/// suggestion (when present); `resolution` reflects the admin's running selection.
struct ImportUserRowModel: Identifiable, Equatable {
    let absUserId: String
    let username: String
    let email: String?
    let suggestedUserId: String?
    let suggestedName: String?
    let resolution: ImportUserResolution

    var id: String { absUserId }

    /// The avatar initial (first letter of the username, uppercased), or "?" when blank.
    var initial: String {
        username.first.map { String($0).uppercased() } ?? "?"
    }

    /// Build from the Kotlin-flattened snapshot, resolving suggestion / assignment names from the
    /// picker users.
    init(snapshot: ImportReviewUserSnapshot, pickerUsers: [ImportPickerUser]) {
        self.absUserId = snapshot.absUserId
        self.username = snapshot.username
        self.email = snapshot.email
        self.suggestedUserId = snapshot.suggestedUserId
        self.suggestedName = snapshot.suggestedUserId.flatMap { suggested in
            pickerUsers.first { $0.id == suggested }?.name
        }
        self.resolution = Self.resolve(snapshot: snapshot, pickerUsers: pickerUsers)
    }

    /// Memberwise init for tests / previews.
    init(
        absUserId: String,
        username: String,
        email: String?,
        suggestedUserId: String?,
        suggestedName: String?,
        resolution: ImportUserResolution
    ) {
        self.absUserId = absUserId
        self.username = username
        self.email = email
        self.suggestedUserId = suggestedUserId
        self.suggestedName = suggestedName
        self.resolution = resolution
    }

    /// The resolution markers the Kotlin `ImportFlowSwiftBridge` emits. Mirrored here as Swift
    /// constants so they can drive a `switch` (the bridge's Kotlin `const val`s aren't usable in
    /// a Swift `case` pattern).
    enum Marker {
        static let assigned = "assigned"
        static let skipped = "skipped"
    }

    /// Pure: classify one ABS user's resolution from its snapshot. An explicit assignment resolves
    /// to the target's display name; an explicit skip to `.skipped`; otherwise `.needsReview`.
    static func resolve(
        snapshot: ImportReviewUserSnapshot,
        pickerUsers: [ImportPickerUser]
    ) -> ImportUserResolution {
        switch snapshot.resolution {
        case Marker.assigned:
            let name = snapshot.assignedUserId.flatMap { assigned in
                pickerUsers.first { $0.id == assigned }?.name
            } ?? snapshot.assignedUserId ?? snapshot.username
            return .assigned(toName: name)
        case Marker.skipped:
            return .skipped
        default:
            return .needsReview
        }
    }
}

// MARK: - Picker user

/// A ListenUp user available for assignment in the Review picker.
struct ImportPickerUser: Identifiable, Equatable {
    let id: String
    let name: String
    let email: String

    init(from user: AdminUserInfo) {
        self.id = user.id
        self.name = user.displayableName
        self.email = user.email
    }

    init(id: String, name: String, email: String) {
        self.id = id
        self.name = name
        self.email = email
    }
}

// MARK: - Done model

/// The completion summary shown on the Done screen.
struct ImportDoneModel: Equatable {
    let importedCount: Int
    let sessionsImported: Int
    let booksNotInLibrary: Int
    let usersUpdated: Int

    init(from result: ImportResult) {
        self.importedCount = Int(result.importedCount)
        self.sessionsImported = Int(result.sessionsImported)
        self.booksNotInLibrary = Int(result.booksNotInLibrary)
        self.usersUpdated = Int(result.perUser.count)
    }

    init(importedCount: Int, sessionsImported: Int, booksNotInLibrary: Int, usersUpdated: Int) {
        self.importedCount = importedCount
        self.sessionsImported = sessionsImported
        self.booksNotInLibrary = booksNotInLibrary
        self.usersUpdated = usersUpdated
    }
}

// MARK: - Progress maths (pure, unit-tested)

/// Pure progress arithmetic shared by the progress model and its tests. Keeps the ring honest:
/// no fabricated fraction before totals arrive, and a clamped, thousands-grouped readout once
/// they do.
enum ImportProgressMath {
    /// The ring fill 0...1, or nil when `total <= 0` (indeterminate). Clamped so a server
    /// over-count never drives the ring past full.
    static func fraction(done: Int, total: Int) -> Double? {
        guard total > 0 else { return nil }
        return min(max(Double(done) / Double(total), 0), 1)
    }

    /// Thousands-grouping formatter for `counterLabel`. Immutable once configured, so it's shared as
    /// a `static let` rather than rebuilt on every progress tick.
    private static let decimalFormatter: NumberFormatter = {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        return formatter
    }()

    /// `"343 / 2,751"`, or nil when no total is known. Thousands-grouped for legibility.
    static func counterLabel(done: Int, total: Int) -> String? {
        guard total > 0 else { return nil }
        let doneText = decimalFormatter.string(from: NSNumber(value: done)) ?? "\(done)"
        let totalText = decimalFormatter.string(from: NSNumber(value: total)) ?? "\(total)"
        return "\(doneText) / \(totalText)"
    }
}
