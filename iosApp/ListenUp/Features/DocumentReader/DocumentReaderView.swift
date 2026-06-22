import PDFKit
import SwiftUI

/// Full-screen basic PDF reader: a glass top bar (Done · title · format), the PDF body,
/// and a "Page X of Y" overlay. Shows an error state instead of a blank view when the
/// file can't be parsed. Presented via `.fullScreenCover(item:)` with a `ReaderDocument`.
struct DocumentReaderView: View {
    let document: ReaderDocument
    var onDone: () -> Void

    @State private var currentPageIndex = 0
    @State private var pageCount = 0

    private var canRender: Bool { PDFDocument(url: document.url) != nil }

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()

            if canRender {
                PDFKitView(url: document.url, currentPageIndex: $currentPageIndex, pageCount: $pageCount)
                    .ignoresSafeArea(edges: .bottom)
            } else {
                errorState
            }
        }
        .safeAreaInset(edge: .top) { topBar }
        .overlay(alignment: .bottom) {
            if canRender, pageCount > 0 { pageIndicator }
        }
    }

    private var topBar: some View {
        HStack {
            Button(String(localized: "common.done"), action: onDone)
                .font(.body.weight(.semibold))
            Spacer()
            VStack(spacing: 1) {
                Text(document.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text("PDF").font(.caption2).foregroundStyle(.secondary)
            }
            Spacer()
            // Symmetry spacer so the title stays centered.
            Button(String(localized: "common.done"), action: {}).opacity(0).disabled(true)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.bar)
    }

    private var pageIndicator: some View {
        let display = pageDisplay(currentIndex: currentPageIndex, pageCount: pageCount)
        let pageOfFormat = String(localized: "book.detail_document_viewer_page_of")
        return Text(String(format: pageOfFormat, display.page, display.total))
            .font(.caption.weight(.medium))
            .monospacedDigit()
            .foregroundStyle(.white)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(.black.opacity(0.55), in: Capsule())
            .padding(.bottom, 16)
    }

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
