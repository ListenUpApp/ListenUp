-- external_refs (matching redesign, PR 1): a book as each metadata catalogue knows it — provider-neutral
-- identity, replacing the Audible-only `asin` as the key matching speaks.
--
--   entity_kind: 'book' today; 'contributor' arrives with the people PR (no series consumer yet).
--   provider:    MetadataProviderId.value ('audible', 'hardcover', 'itunes', 'custom:<name>'). Audnexus keys
--                are Audible ASINs and are stored as 'audible'.
--   region:      the store the key was found in, for a provider that has stores; NULL otherwise.
-- One ref per provider per entity. No FK: entity_kind makes the owner polymorphic, and owners are only
-- ever soft-deleted, so a ref never outlives its row.
--
-- books.asin stays, and stays authoritative for the 'audible' ref: BookRepository reconciles the ref to the
-- column on every write, so an older client editing the ASIN keeps the two in step.

CREATE TABLE external_refs (
    entity_kind TEXT        NOT NULL,
    entity_id   VARCHAR(36) NOT NULL,
    provider    TEXT        NOT NULL,
    external_id TEXT        NOT NULL,
    region      TEXT,
    PRIMARY KEY (entity_kind, entity_id, provider)
);

CREATE INDEX external_refs_by_key ON external_refs (provider, external_id);

INSERT INTO external_refs (entity_kind, entity_id, provider, external_id, region)
SELECT 'book', id, 'audible', TRIM(asin), NULL
FROM books
WHERE asin IS NOT NULL AND TRIM(asin) <> '';
