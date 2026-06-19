-- Persists the operator-configured LISTENUP_METADATA_PRECEDENCE value on the libraries row.
-- The running scanner uses the env-resolved value threaded to the Analyzer; this column is
-- forward-storage for the future per-library Libraries domain phase and is not read back today.
ALTER TABLE libraries ADD COLUMN metadata_precedence TEXT NOT NULL DEFAULT 'metadata.json,embedded,sidecar,filename,folder';
