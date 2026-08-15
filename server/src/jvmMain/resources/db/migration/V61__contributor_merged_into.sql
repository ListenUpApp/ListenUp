-- Contributor merges were undone by rescans: the scanner re-resolves author names from audio
-- tags, and nothing durable mapped a merged-away name back to its survivor. merged_into is that
-- durable, server-only redirect — set on the tombstoned loser row at merge time, consumed by the
-- scanner's resolve, never surfaced to clients. Recording the loser's name as an alias instead
-- was rejected: aliases are user-curated facts only.
ALTER TABLE contributors ADD COLUMN merged_into VARCHAR(36);
