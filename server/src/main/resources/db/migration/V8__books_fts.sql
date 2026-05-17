CREATE VIRTUAL TABLE book_search USING fts5(
    title,
    subtitle,
    description,
    contributor_names,
    series_names,
    content='',          -- contentless; BookRepository manages population manually
    tokenize='unicode61 remove_diacritics 2'
);
