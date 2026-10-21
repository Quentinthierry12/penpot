CREATE TABLE organization (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  owner_id uuid NOT NULL REFERENCES profile(id) ON DELETE RESTRICT DEFERRABLE,
  name text NOT NULL,
  slug text NOT NULL,
  avatar_bg_url text NULL,
  logo_id uuid NULL,
  permissions jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE UNIQUE INDEX organization__slug__idx ON organization (slug);
CREATE INDEX organization__owner_id__idx ON organization (owner_id);

CREATE TRIGGER organization__modified_at__tgr
BEFORE UPDATE ON organization
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TABLE organization_member_rel (
  organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE DEFERRABLE,
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE RESTRICT DEFERRABLE,
  default_team_id uuid NULL REFERENCES team(id) ON DELETE SET NULL DEFERRABLE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  PRIMARY KEY (organization_id, profile_id)
);

CREATE INDEX organization_member_rel__profile_id__idx
    ON organization_member_rel (profile_id);

CREATE UNIQUE INDEX organization_member_rel__default_team_id__idx
    ON organization_member_rel (default_team_id)
 WHERE default_team_id IS NOT NULL;

CREATE TABLE organization_team_rel (
  organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE DEFERRABLE,
  team_id uuid NOT NULL REFERENCES team(id) ON DELETE CASCADE DEFERRABLE,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  PRIMARY KEY (team_id)
);

CREATE INDEX organization_team_rel__organization_id__idx
    ON organization_team_rel (organization_id);

CREATE TABLE organization_sso (
  organization_id uuid PRIMARY KEY REFERENCES organization(id) ON DELETE CASCADE DEFERRABLE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  is_active boolean NOT NULL DEFAULT false,
  provider text NOT NULL DEFAULT 'oidc' CHECK (provider IN ('oidc', 'google')),
  client_id text NOT NULL,
  client_secret text NOT NULL,
  issuer text NULL
);

CREATE TRIGGER organization_sso__modified_at__tgr
BEFORE UPDATE ON organization_sso
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();
