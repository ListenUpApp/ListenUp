-- Where a listener's rating came from: 'listenup' for every rating a person set or edited here,
-- 'hardcover' for one the Hardcover pull imported and nobody has touched since. Synced.
ALTER TABLE book_ratings ADD COLUMN source TEXT NOT NULL DEFAULT 'listenup' CHECK (source IN ('listenup', 'hardcover'));

-- The Hardcover rating the pull last saw for this (book, listener), in half stars: 0 when Hardcover
-- had none, NULL when the pull has never looked. Server-only: it decides whether a cleared rating
-- comes back (only when Hardcover's value has changed since), and never crosses the wire.
ALTER TABLE book_ratings ADD COLUMN hardcover_half_stars INTEGER;
