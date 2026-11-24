import SwiftUI

/// Text, picker, toggle, and date fields for `BookEditView` (identity at the top, catalog
/// metadata at the bottom) and their Return-key focus chain.
extension BookEditView {
    /// Identity fields at the top of the form (positions 1–5 of the people-first order): Title,
    /// Subtitle, Sort Title, Description. Publishing/identifier metadata lives in `catalogFields` at
    /// the bottom so the order matches Android's Book Edit screen.
    @ViewBuilder
    func identityFields(_ observer: BookEditObserver) -> some View {
        Group {
            AppTextField(
                placeholder: "",
                text: Binding(get: { observer.title }, set: { observer.setTitle($0) }),
                entry: .words,
                label: String(localized: "book.edit_title_field"),
                submitLabel: BookEditFocusField.title.submitLabel(last: .done),
                onSubmit: { advance(from: .title) }
            )
            .focused($focusedField, equals: .title)

            AppTextField(
                placeholder: String(localized: "book.edit_add_subtitle"),
                text: Binding(get: { observer.subtitle }, set: { observer.setSubtitle($0) }),
                entry: .words,
                label: String(localized: "book.edit_subtitle"),
                submitLabel: BookEditFocusField.subtitle.submitLabel(last: .done),
                onSubmit: { advance(from: .subtitle) }
            )
            .focused($focusedField, equals: .subtitle)

            AppTextField(
                placeholder: String(localized: "book.edit_eg_lord_of_the_rings"),
                text: Binding(get: { observer.sortTitle }, set: { observer.setSortTitle($0) }),
                entry: .words,
                label: String(localized: "book.edit_sort_title"),
                submitLabel: BookEditFocusField.sortTitle.submitLabel(last: .done),
                onSubmit: { advance(from: .sortTitle) }
            )
            .focused($focusedField, equals: .sortTitle)

            AppTextField(
                placeholder: String(localized: "book.edit_description_placeholder"),
                text: Binding(get: { observer.bookDescription }, set: { observer.setDescription($0) }),
                entry: .sentences,
                label: String(localized: "book.edit_description_label"),
                axis: .vertical
            )
        }
    }

    /// Catalog metadata at the bottom of the form (positions 12–18): Publisher, Year, Language,
    /// ISBN, ASIN, Abridged, Date Added. Mirrors Android's Publishing → Identifiers → Library cards.
    @ViewBuilder
    func catalogFields(_ observer: BookEditObserver) -> some View {
        Group {
            AppTextField(
                placeholder: "",
                text: Binding(get: { observer.publisher }, set: { observer.setPublisher($0) }),
                entry: .words,
                label: String(localized: "book.edit_publisher"),
                submitLabel: BookEditFocusField.publisher.submitLabel(last: .done),
                onSubmit: { advance(from: .publisher) }
            )
            .focused($focusedField, equals: .publisher)

            AppTextField(
                placeholder: "",
                text: Binding(get: { observer.publishYear }, set: { observer.setPublishYear($0) }),
                entry: .number,
                label: String(localized: "book.edit_year"),
                submitLabel: BookEditFocusField.year.submitLabel(last: .done),
                onSubmit: { advance(from: .year) }
            )
            .focused($focusedField, equals: .year)

            languageField(observer)

            AppTextField(
                placeholder: "",
                text: Binding(get: { observer.isbn }, set: { observer.setIsbn($0) }),
                entry: .identifier,
                label: String(localized: "book.edit_isbn"),
                submitLabel: BookEditFocusField.isbn.submitLabel(last: .done),
                onSubmit: { advance(from: .isbn) }
            )
            .focused($focusedField, equals: .isbn)

            AppTextField(
                placeholder: "",
                text: Binding(get: { observer.asin }, set: { observer.setAsin($0) }),
                entry: .identifier,
                label: String(localized: "book.edit_asin"),
                submitLabel: BookEditFocusField.asin.submitLabel(last: .done),
                onSubmit: { advance(from: .asin) }
            )
            .focused($focusedField, equals: .asin)

            abridgedField(observer)
            addedAtField(observer)
        }
    }

    /// Return moves along a run of single-line fields; at the end of a run it puts the keyboard
    /// away. Saving stays on the Done button — a Return that saved a whole book would be a surprise.
    private func advance(from field: BookEditFocusField) {
        FormFocus.advance(from: field, focus: $focusedField) { focusedField = nil }
    }

    /// Language picker over the shared ISO 639-1 list, with a "None" option that clears it.
    private func languageField(_ observer: BookEditObserver) -> some View {
        // A system picker row: the label leads, the chosen value trails and opens a menu.
        Picker(
            String(localized: "book.edit_language"),
            selection: Binding(get: { observer.language }, set: { observer.setLanguage($0) })
        ) {
            Text(String(localized: "book.edit_language_none")).tag("")
            ForEach(observer.languageOptions) { choice in
                Text(choice.name).tag(choice.code)
            }
        }
        .pickerStyle(.menu)
    }

    /// Abridged toggle.
    private func abridgedField(_ observer: BookEditObserver) -> some View {
        Toggle(isOn: Binding(get: { observer.abridged }, set: { observer.setAbridged($0) })) {
            Text(String(localized: "book.edit_abridged"))
                .font(.body)
        }
    }

    /// Date-added row: a date picker when set (with a clear button), or a "Set date" button when unset.
    private func addedAtField(_ observer: BookEditObserver) -> some View {
        LabeledFieldRow(label: String(localized: "book.edit_date_added")) {
            HStack {
                if let date = observer.addedAt {
                    DatePicker(
                        "",
                        selection: Binding(get: { date }, set: { observer.setAddedAt($0) }),
                        displayedComponents: .date
                    )
                    .labelsHidden()
                    Spacer()
                    Button {
                        observer.setAddedAt(nil)
                    } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(String(localized: "common.clear_date"))
                } else {
                    Button(String(localized: "common.set_date")) { observer.setAddedAt(Date()) }
                        .buttonStyle(.plain)
                        .foregroundStyle(Color.luTint)
                    Spacer()
                }
            }
        }
    }
}
