import Foundation
import Shared

/// One face in a chapter's stack of readers, as a native value. Mirrors the shared `ChapterReaderFace`.
struct ChapterReaderFaceRow: Identifiable, Equatable {
    /// Their user id: the avatar's seed.
    let id: String
    /// Their full name: the avatar's initials.
    let displayName: String
    /// Listening to this book right now: the coral ring and the breathing glow.
    let isLive: Bool
}

/// Where a stack of readers sits, which picks the wording of its sentence. Mirrors the shared `ReadersPlace`.
enum ChapterReadersPlace: Equatable {
    case thisChapter
    case thisPart
    case furtherOn
    case earlier
    case otherChapters

    init(_ place: ReadersPlace) {
        switch place {
        case .ThisChapter: self = .thisChapter
        case .ThisPart: self = .thisPart
        case .FurtherOn: self = .furtherOn
        case .Earlier: self = .earlier
        case .OtherChapters: self = .otherChapters
        }
    }
}

/// One clause of a stack's sentence: one or two first names, then how many go unnamed. Mirrors the shared
/// `ReaderClause`, whose `form` decides the wording.
struct ChapterReadersClause: Equatable {
    /// The four wordings, as the hero's social line has them.
    enum Form: Equatable {
        /// "Maya is …"
        case one
        /// "Maya and Tom are …"
        case two
        /// "Maya, Tom and 1 other are …"
        case twoAndOneOther
        /// "Maya, Tom and N others are …"
        case twoAndOthers
    }

    let names: [String]
    let othersCount: Int
    let form: Form

    init(names: [String], othersCount: Int, form: Form) {
        self.names = names
        self.othersCount = othersCount
        self.form = form
    }

    init(_ clause: ReaderClause) {
        names = clause.named.map(\.firstName)
        othersCount = Int(clause.othersCount)
        form = switch clause.form {
        case .One: .one
        case .Two: .two
        case .TwoAndOneOther: .twoAndOneOther
        case .TwoAndOthers: .twoAndOthers
        }
    }

    /// The name at `index`, or nothing: the form says how many there are, so a short list never traps.
    func name(_ index: Int) -> String { names.indices.contains(index) ? names[index] : "" }
}

/// The avatars one place in the chapter list carries, and the sentence VoiceOver says about them. Projected once
/// from the shared `ChapterReaders`, so the view never holds a Kotlin object.
struct ChapterReaderStack: Equatable {
    let place: ChapterReadersPlace
    /// The circles with faces: everyone up to three, else the first two beside the "+N" disc.
    let faces: [ChapterReaderFaceRow]
    /// The number on the "+N" disc; 0 draws none.
    let overflowCount: Int
    let listeningNow: ChapterReadersClause?
    let notListening: ChapterReadersClause?

    init(place: ChapterReadersPlace, faces: [ChapterReaderFaceRow], overflowCount: Int,
         listeningNow: ChapterReadersClause?, notListening: ChapterReadersClause?) {
        self.place = place
        self.faces = faces
        self.overflowCount = overflowCount
        self.listeningNow = listeningNow
        self.notListening = notListening
    }

    /// Reads each bridged property once.
    init(_ readers: ChapterReaders) {
        self.init(
            place: ChapterReadersPlace(readers.place),
            faces: readers.faces.map { ChapterReaderFaceRow(id: $0.userId, displayName: $0.displayName, isLive: $0.isLive) },
            overflowCount: Int(readers.overflowCount),
            listeningNow: readers.listeningNow.map(ChapterReadersClause.init),
            notListening: readers.notListening.map(ChapterReadersClause.init)
        )
    }

    /// "Maya is listening now. Priya, Tom and 2 others are on this chapter.": who is listening first, then the rest.
    var sentence: String {
        [
            listeningNow.map { ClauseWording.listeningNow.render($0) },
            notListening.map { ClauseWording.notListening(place).render($0) }
        ]
        .compactMap { $0 }
        .joined(separator: " ")
    }
}

/// The four wordings of one kind of clause. Every key is a literal inside `String(localized:)`, where
/// `verifySwiftStringKeys` can see it.
private struct ClauseWording {
    let one: String
    let two: String
    let twoAndOneOther: String
    let twoAndOthers: String

    func render(_ clause: ChapterReadersClause) -> String {
        switch clause.form {
        case .one: String(format: one, clause.name(0))
        case .two: String(format: two, clause.name(0), clause.name(1))
        case .twoAndOneOther: String(format: twoAndOneOther, clause.name(0), clause.name(1))
        case .twoAndOthers: String(format: twoAndOthers, clause.name(0), clause.name(1), clause.othersCount)
        }
    }

    static var listeningNow: ClauseWording {
        ClauseWording(
            one: String(localized: "book.detail_chapter_readers_listening_now_one"),
            two: String(localized: "book.detail_chapter_readers_listening_now_two"),
            twoAndOneOther: String(localized: "book.detail_chapter_readers_listening_now_many_one"),
            twoAndOthers: String(localized: "book.detail_chapter_readers_listening_now_many")
        )
    }

    static func notListening(_ place: ChapterReadersPlace) -> ClauseWording {
        switch place {
        case .thisChapter:
            ClauseWording(
                one: String(localized: "book.detail_chapter_readers_on_chapter_one"),
                two: String(localized: "book.detail_chapter_readers_on_chapter_two"),
                twoAndOneOther: String(localized: "book.detail_chapter_readers_on_chapter_many_one"),
                twoAndOthers: String(localized: "book.detail_chapter_readers_on_chapter_many")
            )
        case .thisPart:
            ClauseWording(
                one: String(localized: "book.detail_chapter_readers_in_part_one"),
                two: String(localized: "book.detail_chapter_readers_in_part_two"),
                twoAndOneOther: String(localized: "book.detail_chapter_readers_in_part_many_one"),
                twoAndOthers: String(localized: "book.detail_chapter_readers_in_part_many")
            )
        case .furtherOn:
            ClauseWording(
                one: String(localized: "book.detail_chapter_readers_further_on_one"),
                two: String(localized: "book.detail_chapter_readers_further_on_two"),
                twoAndOneOther: String(localized: "book.detail_chapter_readers_further_on_many_one"),
                twoAndOthers: String(localized: "book.detail_chapter_readers_further_on_many")
            )
        case .earlier:
            ClauseWording(
                one: String(localized: "book.detail_chapter_readers_earlier_one"),
                two: String(localized: "book.detail_chapter_readers_earlier_two"),
                twoAndOneOther: String(localized: "book.detail_chapter_readers_earlier_many_one"),
                twoAndOthers: String(localized: "book.detail_chapter_readers_earlier_many")
            )
        case .otherChapters:
            ClauseWording(
                one: String(localized: "book.detail_chapter_readers_other_chapters_one"),
                two: String(localized: "book.detail_chapter_readers_other_chapters_two"),
                twoAndOneOther: String(localized: "book.detail_chapter_readers_other_chapters_many_one"),
                twoAndOthers: String(localized: "book.detail_chapter_readers_other_chapters_many")
            )
        }
    }
}

/// Where everyone else is in the book, asked of the chapter list place by place. Wraps the shared
/// `ChapterReadership`, projecting each answer to a native `ChapterReaderStack` as the list asks for it.
struct ChapterReadersLookup {
    private let onChapterStack: (String) -> ChapterReaderStack?
    private let inPartStack: (String) -> ChapterReaderStack?
    private let beyondStack: ([String]) -> ChapterReaderStack?

    init(onChapter: @escaping (String) -> ChapterReaderStack?,
         inPart: @escaping (String) -> ChapterReaderStack?,
         beyond: @escaping ([String]) -> ChapterReaderStack?) {
        onChapterStack = onChapter
        inPartStack = inPart
        beyondStack = beyond
    }

    init(_ readership: ChapterReadership) {
        self.init(
            onChapter: { readership.onChapter(chapterId: $0).map(ChapterReaderStack.init) },
            inPart: { readership.inPart(partId: $0).map(ChapterReaderStack.init) },
            beyond: { readership.beyond(shownChapterIds: $0).map(ChapterReaderStack.init) }
        )
    }

    /// Nobody on any chapter.
    static var none: ChapterReadersLookup {
        ChapterReadersLookup(onChapter: { _ in nil }, inPart: { _ in nil }, beyond: { _ in nil })
    }

    /// The readers on this chapter's row; an open part's header asks this for the chapter folded into it.
    func onChapter(_ chapterId: String) -> ChapterReaderStack? { onChapterStack(chapterId) }

    /// The readers anywhere in a collapsed part, by the part header's chapter id.
    func inPart(_ partId: String) -> ChapterReaderStack? { inPartStack(partId) }

    /// The readers on chapters whose rows are not shown, for the collapsed list's "All N Chapters".
    func beyond(_ shownChapterIds: [String]) -> ChapterReaderStack? { beyondStack(shownChapterIds) }
}
