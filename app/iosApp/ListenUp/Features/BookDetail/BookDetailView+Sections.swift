import SwiftUI

/// The order of Book Detail's content below the description, which both layouts walk: the social sections
/// (Ratings, Readers, Hardcover) come before Chapters, as on Android and web.
enum BookDetailContentSection: CaseIterable {
    case social
    case chapters
    case documents
    case details
}

extension BookDetailView {
    /// Book Detail's content sections, in ``BookDetailContentSection``'s order. Each section draws its own
    /// leading divider, so a section that renders nothing leaves no stray line.
    @ViewBuilder
    func contentSections(_ observer: BookDetailObserver) -> some View {
        ForEach(BookDetailContentSection.allCases, id: \.self) { section in
            switch section {
            case .social:
                if observer.layout.showsSocial {
                    ratingSection
                    readersSection
                    hardcoverSection
                }
            case .chapters:
                Divider()
                BookChaptersSection(chapters: observer.chapters)
            case .documents:
                if !observer.documents.isEmpty {
                    Divider()
                    SupplementaryMaterialsSection(
                        documents: observer.documents,
                        openingDocIds: observer.openingDocIds,
                        onOpen: { observer.openDocument(docId: $0) }
                    )
                }
            case .details:
                Divider()
                detailsSection(observer)
            }
        }
    }
}
