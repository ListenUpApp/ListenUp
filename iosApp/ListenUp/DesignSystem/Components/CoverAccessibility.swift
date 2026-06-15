import Foundation

/// Composes the VoiceOver label for a book cover image from its title and author.
/// A cover with no usable text returns `nil` — the caller then hides it as decorative
/// rather than leaving VoiceOver to read a meaningless image.
enum CoverAccessibility {
    static func label(title: String?, author: String?) -> String? {
        let t = title?.trimmingCharacters(in: .whitespacesAndNewlines)
        let a = author?.trimmingCharacters(in: .whitespacesAndNewlines)
        let titlePart = (t?.isEmpty == false) ? t : nil
        let authorPart = (a?.isEmpty == false) ? a : nil
        switch (titlePart, authorPart) {
        case let (title?, author?): return "\(title), \(author)"
        case let (title?, nil): return title
        case let (nil, author?): return author
        case (nil, nil): return nil
        }
    }
}
