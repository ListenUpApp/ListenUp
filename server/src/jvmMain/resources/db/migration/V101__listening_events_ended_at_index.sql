-- Discover's recent listens read every OTHER user's listens that ended since a cut-off (`user_id != ?` and
-- `ended_at >= ?`). Every other listening_events index leads with user_id, which a `!=` cannot use, so the query
-- scanned the whole table on each client's poll; this one lets it read the recent range instead.
CREATE INDEX idx_listening_events_ended_at ON listening_events(ended_at);
