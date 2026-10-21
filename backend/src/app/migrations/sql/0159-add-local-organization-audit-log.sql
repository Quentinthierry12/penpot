CREATE TABLE organization_audit_log (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE DEFERRABLE,
  event_name text NOT NULL,
  profile_id uuid NULL REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE,
  occurred_at timestamptz NOT NULL,
  event jsonb NOT NULL
);

CREATE INDEX organization_audit_log__organization_id_occurred_at__idx
    ON organization_audit_log (organization_id, occurred_at DESC, id DESC);
