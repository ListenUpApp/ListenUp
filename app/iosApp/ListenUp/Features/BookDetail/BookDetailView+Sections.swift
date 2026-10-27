import SwiftUI

/// Book Detail's sections, in the visit's stage order (spec §2.4) — the order the shared ViewModel decided when the
/// page opened, and kept while it stays open. Both layouts walk it: the iPhone as one column, the iPad as
/// `BookDetailIPadRows`. A section with nothing to show renders nothing (skipped, not left as a gap).
@MainActor
extension BookDetailView {
    /// The Readers section's scroll anchor: the social line brings it into view, and so can a landing on it.
    static let readersAnchor = "readers"

    /// The sections this page shows. A held book is triage-only (spec §3.2.3): About, Chapters and Details stay; the
    /// social sections are hidden (`BookDetailLayout.showsSocial`).
    func shownSections(_ observer: BookDetailObserver) -> [BookDetailSectionKind] {
        Self.shown(observer.reading.sectionOrder, layout: observer.layout)
    }

    /// The pure rule behind `shownSections(_:)`.
    nonisolated static func shown(
        _ order: [BookDetailSectionKind],
        layout: BookDetailLayout
    ) -> [BookDetailSectionKind] {
        guard !layout.showsSocial else { return order }
        return order.filter { $0 == .about || $0 == .chapters || $0 == .details }
    }

    /// One section.
    @ViewBuilder
    func section(_ kind: BookDetailSectionKind, _ observer: BookDetailObserver, isWide: Bool) -> some View {
        switch kind {
        case .chapters:
            if !observer.chapters.isEmpty {
                BookChapterRowsSection(
                    chapters: observer.chapters,
                    preview: observer.reading.chapterPreview,
                    canPlay: observer.canPlay,
                    onPlayFromChapter: { observer.play(fromChapterStartMs: $0) }
                )
            }
        case .about:
            BookDescriptionSection(
                description: observer.bookDescription,
                genres: observer.genres,
                tags: observer.tags,
                moods: observer.moods,
                lineLimit: isWide ? nil : (observer.reading.stage == .notStarted ? 4 : 2)
            )
        case .readers:
            readersSection
        case .ratings:
            ratingSection
        case .hardcover:
            hardcoverSection
        case .details:
            detailsSection(observer)
        }
    }

    /// The Details rows; All Credits opens Cast & Credits, Files & Audio its sheet, and Maps & Illustrations the one
    /// document at once or the list of several (spec D31, D32, D36).
    func detailsSection(_ observer: BookDetailObserver) -> some View {
        BookDetailsSection(rows: observer.detailRows) { link in
            switch link {
            case .credits: showCast = true
            case .filesAndAudio: detailsSheet = .filesAndAudio
            case .document(let id): observer.openDocument(docId: id)
            case .documents: detailsSheet = .documents
            }
        }
    }

    /// Files & Audio or Maps & Illustrations, at a medium detent that can grow (HIG, Sheets).
    @ViewBuilder
    func detailsSheetContent(_ sheet: BookDetailsSheet) -> some View {
        if let observer {
            Group {
                switch sheet {
                case .filesAndAudio:
                    FilesAndAudioSheet(files: observer.audioFiles, format: observer.audioFormat) { detailsSheet = nil }
                case .documents:
                    DocumentsSheet(
                        documents: observer.documents,
                        openingDocIds: observer.openingDocIds,
                        onOpen: { observer.openDocument(docId: $0) },
                        onDone: { detailsSheet = nil }
                    )
                }
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
    }

    /// The iPad main column: the stage order a row at a time, with Ratings beside Chapters when in progress and the
    /// column has the room (`BookDetailIPadRows`).
    @ViewBuilder
    func iPadRows(_ observer: BookDetailObserver) -> some View {
        let rows = BookDetailIPadRows.rows(
            order: shownSections(observer),
            stage: observer.reading.stage,
            mainWidth: mainColumnWidth
        )
        ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
            if row.count == 1 {
                section(row[0], observer, isWide: true)
            } else {
                HStack(alignment: .top, spacing: 20) {
                    ForEach(row, id: \.self) { kind in
                        section(kind, observer, isWide: true)
                            .frame(maxWidth: .infinity, alignment: .topLeading)
                    }
                }
            }
        }
    }

    /// Who else is reading, under the actions (spec §2.10): one element that brings Readers into view. Hidden for a
    /// held book and when nobody but you has read it.
    @ViewBuilder
    func socialLine(_ observer: BookDetailObserver, proxy: ScrollViewProxy) -> some View {
        if observer.layout.showsSocial, let line = readersObserver?.socialLine {
            HeroSocialLineView(
                line: line,
                ringColor: heroWash,
                onOpenReaders: { withAnimation { proxy.scrollTo(Self.readersAnchor, anchor: .top) } }
            )
        }
    }
}
