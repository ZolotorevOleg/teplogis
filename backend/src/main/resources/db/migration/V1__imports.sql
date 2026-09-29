CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE TABLE imports (
  id uuid PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT now(),
  feature_count bigint NOT NULL DEFAULT 0,
  byte_count bigint NOT NULL,
  status varchar(16) NOT NULL CHECK (status IN ('VALIDATING','READY'))
);
-- Canonical JSON scalar preserves string/number distinction. Text also supports escaped NUL.
CREATE TABLE input_objects (
  import_id uuid NOT NULL REFERENCES imports(id) ON DELETE CASCADE,
  object_id text NOT NULL,
  object_type varchar(32) NOT NULL,
  ordinal bigint NOT NULL,
  PRIMARY KEY (import_id, ordinal)
);
CREATE UNIQUE INDEX input_objects_id_unique ON input_objects(import_id, digest(object_id, 'sha256'));
