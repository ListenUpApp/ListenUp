-- The library's Audible store (matching redesign, PR 1): a MetadataLocale.region token ('us', 'uk', …) a
-- match search starts in unless the person picks another for that one search. NULL = the server default
-- (the United States). Set by an admin; carried to clients on LibrarySyncPayload.
ALTER TABLE libraries ADD COLUMN metadata_region TEXT;
