import SwiftUI

/// Book Detail's hero and its listen unit — the Resume unit over the actions — shared by the iPhone stack and the
/// iPad rail. Split out of `BookDetailView.swift`, which sits at SwiftLint's type-body cap; the presentation state
/// stays on the view, because `@State` storage cannot live in an extension.
@MainActor
extension BookDetailView {
    func hero(_ observer: BookDetailObserver) -> some View {
        BookDetailHero(
            header: observer.header,
            title: observer.title,
            subtitle: observer.subtitle,
            seriesPaths: observer.seriesPaths,
            authors: observer.heroAuthors,
            author: observer.authors,
            narrators: observer.heroNarrators,
            narratorsText: observer.narrators,
            duration: BookDetailHero.durationText(ms: observer.durationMs),
            year: observer.year,
            isAbridged: observer.isAbridged,
            onOpenCast: { showCast = true },
            onTitleMaxY: { maxY in
                let visible = BookDetailInlineTitle.isVisible(heroTitleMaxY: maxY)
                if visible != showsInlineTitle { showsInlineTitle = visible }
            }
        )
    }

    /// The Resume unit over the actions (spec §2.9): where you are with the book, then what to do next.
    func listenUnit(_ observer: BookDetailObserver) -> some View {
        VStack(spacing: 14) {
            ResumeUnit(
                stage: observer.reading.stage,
                currentChapter: observer.reading.currentChapter,
                timeRemainingMs: observer.reading.timeRemainingMs,
                progress: observer.progress,
                finishedSpan: observer.reading.finishedSpan,
                cover: coverTheme.colors
            )
            // Why the primary below is disabled, directly above it (spec §2.9).
            if observer.reading.primaryAction == .unavailableOffline {
                OfflineReasonView { observer.retryConnection() }
            }
            BookListenActions(
                label: BookPrimaryActionLabel(kind: observer.reading.primaryAction,
                                              isPreparing: observer.isPlayPending),
                downloadState: observer.downloadState,
                downloadProgress: observer.downloadProgress,
                downloadSizeBytes: observer.reading.downloadSizeBytes,
                canDownload: observer.canDownload,
                onPrimary: { observer.play() },
                onDownload: {
                    bookActionCount += 1
                    observer.downloadBook()
                },
                onCancelDownload: { observer.cancelDownload() },
                onDeleteDownload: {
                    bookActionCount += 1
                    observer.deleteDownload()
                },
                onAddToShelf: { observer.openShelfPicker() },
                actionTint: coverTheme.colors?.action
            )
        }
    }
}
