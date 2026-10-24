import PDFKit
import SwiftUI

/// Full-screen PDF reader with immersive chrome: tap to toggle the top bar and bottom dock
/// (scrubber + now-playing strip). A folio hint pill anchors the top when chrome is hidden
/// so the reader always knows their position. With room — an iPad, an iPhone Duo half open like a book — the
/// listening pane sits beside the page in place of the strip (`ReaderLayoutMode`).
struct DocumentReaderView: View {
    let document: ReaderDocument
    var onDone: () -> Void

    @State private var pdfDocument: PDFDocument?
    @State private var didAttemptLoad = false
    @State private var currentPageIndex = 0
    @State private var chromeVisible = true
    @State private var goToPage: Int?
    @State private var scrubFraction: Double = 0
    @State private var showGrid = false
    @State private var showSearch = false
    @State private var highlightSelection: PDFSelection?
    @State private var clearHighlight = false
    @State private var search: PdfSearchController?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.verticalSizeClass) private var vSize
    @Environment(\.layoutDirection) private var layoutDirection
    @Environment(\.dependencies) private var dependencies

    private var pageCount: Int { pdfDocument?.pageCount ?? 0 }

    var body: some View {
        // The layout follows the fold and the size classes (`ReaderLayoutMode`): with room, the player's own controls sit
        // beside the page in place of the strip — the same player observer the strip reads, so it is never a second
        // player, and opening the reader from Now Playing leaves Now Playing as it was underneath.
        GeometryReader { proxy in
            let mode = ReaderLayoutMode.resolve(
                horizontalSizeClass: hSize,
                verticalSizeClass: vSize,
                fold: PlayerFold.from(proxy),
                hasCurrentBook: dependencies.playerObserver.isVisible
            )
            reader(mode, width: proxy.size.width)
                .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: mode)
        }
        .task {
            pdfDocument = PDFDocument(url: document.url)
            if let pdfDocument { search = PdfSearchController(document: pdfDocument) }
            didAttemptLoad = true
        }
        .fullScreenCover(isPresented: $showGrid) {
            if let pdfDocument {
                PageGridView(
                    document: pdfDocument,
                    currentPageIndex: currentPageIndex,
                    onSelect: { idx in goToPage = idx; showGrid = false },
                    onClose: { showGrid = false }
                )
            }
        }
        .fullScreenCover(isPresented: $showSearch) {
            if let search {
                DocumentSearchView(
                    controller: search,
                    onSelect: { sel in highlightSelection = sel; showSearch = false },
                    onClose: { search.cancel(); clearHighlight = true; showSearch = false }
                )
            }
        }
    }

    // MARK: - Layout

    /// The page alone with its dock under it, or the page and the listening pane side by side. The top bar spans both;
    /// beside the pane, the page keeps its scrubber under it and the strip gives way to the pane.
    @ViewBuilder
    private func reader(_ mode: ReaderLayoutMode, width: CGFloat) -> some View {
        // The rail is regular width's always-on page picker; it gives the width to the pane when the pane shows.
        let showsRail = hSize == .regular && mode == .single
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            switch mode {
            case .single:
                pageArea(showsRail: showsRail)
            case .beside(let split):
                let columns = ReaderColumns(split: split, width: width, layoutDirection: layoutDirection)
                HStack(spacing: 0) {
                    pageArea(showsRail: false)
                        .safeAreaBar(edge: .bottom) {
                            if chromeVisible, pageCount > 0 {
                                scrubberRow
                                    .padding(.horizontal, Spacing.m)
                                    .padding(.vertical, Spacing.s)
                            }
                        }
                        .frame(width: columns.page)
                    Color.clear.frame(width: columns.gap)
                    ReaderListeningPane(observer: dependencies.playerObserver)
                        .frame(width: columns.pane)
                }
            }
        }
        .safeAreaBar(edge: .top) {
            if chromeVisible { topBar(showsRail: showsRail) }
        }
        .safeAreaBar(edge: .bottom) {
            if mode == .single, chromeVisible, pdfDocument != nil, pageCount > 0 { bottomDock }
        }
        .overlay(alignment: .top) {
            if !chromeVisible, pageCount > 0 { folioHint }
        }
    }

    /// The PDF — beside the page rail on regular width — or its loading and error states.
    @ViewBuilder
    private func pageArea(showsRail: Bool) -> some View {
        if let pdfDocument {
            if showsRail {
                HStack(spacing: 0) {
                    PageRailView(
                        document: pdfDocument,
                        currentPageIndex: currentPageIndex,
                        onSelect: { goToPage = $0 }
                    )
                    pdfView(document: pdfDocument)
                }
            } else {
                pdfView(document: pdfDocument)
            }
        } else if didAttemptLoad {
            errorState
        } else {
            ProgressView()
        }
    }

    // MARK: - PDF view

    @ViewBuilder
    private func pdfView(document: PDFDocument) -> some View {
        PDFKitView(
            document: document,
            currentPageIndex: $currentPageIndex,
            goToPage: $goToPage,
            highlightSelection: $highlightSelection,
            clearHighlight: $clearHighlight
        )
        .ignoresSafeArea(edges: .bottom)
        .contentShape(Rectangle())
        .onTapGesture {
            withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) {
                chromeVisible.toggle()
            }
        }
        .onChange(of: currentPageIndex) { _, newIndex in
            scrubFraction = pageCount > 1
                ? Double(newIndex) / Double(pageCount - 1)
                : 0
        }
    }

    // MARK: - Top bar

    private func topBar(showsRail: Bool) -> some View {
        HStack {
            Button(String(localized: "common.done"), action: onDone)
                .font(.body.weight(.semibold))
            Spacer()
            HStack(spacing: 8) {
                if search != nil {
                    Button { showSearch = true } label: {
                        Image(systemName: "magnifyingglass")
                    }
                    .accessibilityLabel(String(localized: "book.detail_document_search"))
                }
                // The rail is the always-on equivalent; hide the grid button while it shows.
                if !showsRail {
                    Button { showGrid = true } label: {
                        Image(systemName: "square.grid.2x2")
                    }
                    .accessibilityLabel(String(localized: "book.detail_document_reader_toggle_grid"))
                }
            }
        }
        .overlay {
            VStack(spacing: 1) {
                Text(document.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text("PDF").font(.caption2).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 80)
        }
        .padding(.horizontal, Spacing.m)
        .padding(.vertical, Spacing.s)
    }

    // MARK: - Bottom dock

    private var bottomDock: some View {
        VStack(spacing: 8) {
            NowPlayingStrip()
            scrubberRow
        }
        .padding(.horizontal, Spacing.m)
        .padding(.vertical, Spacing.s)
    }

    private var scrubberRow: some View {
        let display = pageDisplay(currentIndex: currentPageIndex, pageCount: pageCount)
        return HStack(spacing: 8) {
            Text(String(format: String(localized: "book.reader_scrubber_page_label"), display.page))
                .font(.caption.weight(.medium))
                .monospacedDigit()
                .foregroundStyle(.secondary)
                .frame(minWidth: 32, alignment: .leading)
                .accessibilityHidden(true)

            Slider(value: $scrubFraction, in: 0...1) { editing in
                if !editing {
                    goToPage = scrubberPage(fraction: scrubFraction, pageCount: pageCount) - 1
                }
            }
            // A page, not a fraction: "Page 3 of 40", and each VoiceOver swipe turns one page
            // straight away rather than nudging a thumb that only commits on release.
            .accessibilityLabel(String(localized: "book.reader_scrubber_a11y"))
            .accessibilityValue(String(
                format: String(localized: "book.detail_document_viewer_page_of"), display.page, display.total
            ))
            .accessibilityAdjustableAction { direction in
                goToPage = adjustedPageIndex(
                    from: currentPageIndex,
                    pageCount: pageCount,
                    forward: direction == .increment
                )
            }

            Text(String(format: String(localized: "book.reader_scrubber_page_count"), pageCount))
                .font(.caption.weight(.medium))
                .monospacedDigit()
                .foregroundStyle(.secondary)
                .frame(minWidth: 32, alignment: .trailing)
                .accessibilityHidden(true)
        }
    }

    // MARK: - Folio hint

    private var folioHint: some View {
        let display = pageDisplay(currentIndex: currentPageIndex, pageCount: pageCount)
        let format = String(localized: "book.detail_document_reader_controls_hint")
        return Text(String(format: format, display.page, display.total))
            .font(.caption.weight(.medium))
            .monospacedDigit()
            .foregroundStyle(.white)
            .padding(.horizontal, Spacing.s)
            .padding(.vertical, Spacing.xs)
            .background(.black.opacity(0.55), in: Capsule())
            .padding(.top, Spacing.xs)
    }

    // MARK: - Error state

    private var errorState: some View {
        VStack(spacing: 8) {
            Image(systemName: "doc.questionmark").font(.largeTitle).foregroundStyle(.secondary)
            Text(String(localized: "book.detail_document_viewer_error")).foregroundStyle(.secondary)
        }
    }
}

/// Identifiable payload for `.fullScreenCover(item:)`. `id` is the local path so re-tapping
/// the same document re-presents cleanly.
struct ReaderDocument: Identifiable, Equatable {
    let id: String
    let url: URL
    let title: String

    init(localPath: String, title: String) {
        self.id = localPath
        self.url = URL(fileURLWithPath: localPath)
        self.title = title
    }
}
