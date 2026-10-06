-- Contributor refs and provenance (matching redesign, PR 4).
--
-- 1. contributors.field_provenance: a JSON object keyed by ContributorField (NAME, SORT_NAME, BIOGRAPHY, PHOTO),
--    the same FieldProvenance values books.field_provenance holds. '{}' = nothing tracked: a field with no USER
--    entry is not a hand edit (decision 9).
-- 2. The contributor backfill into external_refs. contributors.asin holds an Audnexus key, which is an Audible
--    ASIN, or the legacy Hardcover apply's 'hardcover:author:<id>'. The column stays, and stays authoritative for
--    the ref its value names: ContributorRepository reconciles the refs to it on every write.

ALTER TABLE contributors ADD COLUMN field_provenance TEXT NOT NULL DEFAULT '{}';

INSERT INTO external_refs (entity_kind, entity_id, provider, external_id, region)
SELECT 'contributor',
       id,
       CASE WHEN TRIM(asin) LIKE 'hardcover:author:%' THEN 'hardcover' ELSE 'audible' END,
       CASE WHEN TRIM(asin) LIKE 'hardcover:author:%' THEN SUBSTR(TRIM(asin), 18) ELSE TRIM(asin) END,
       NULL
FROM contributors
WHERE asin IS NOT NULL AND TRIM(asin) <> '';
