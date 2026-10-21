import Provider from "oidc-provider";

const issuer = process.env.OIDC_TEST_ISSUER ?? "http://localhost:9000";
const clientId = process.env.OIDC_TEST_CLIENT_ID ?? "penpot-local-test";
const clientSecret = process.env.OIDC_TEST_CLIENT_SECRET ?? "local-only-test-secret";
const email = process.env.OIDC_TEST_EMAIL ?? "sso-test@example.test";
const groups = (process.env.OIDC_TEST_GROUPS ?? "club-rezo-asso_tech,role-asso_membre")
  .split(",")
  .map((group) => group.trim())
  .filter(Boolean);
const redirectUri = process.env.OIDC_TEST_REDIRECT_URI ?? "http://localhost:9001/api/auth/oidc/callback";

const provider = new Provider(issuer, {
  clients: [
    {
      client_id: clientId,
      client_secret: clientSecret,
      redirect_uris: [redirectUri],
      response_types: ["code"],
      grant_types: ["authorization_code"],
      token_endpoint_auth_method: "client_secret_post",
    },
  ],
  scopes: ["openid", "profile", "email"],
  claims: {
    openid: ["sub"],
    profile: ["name", "groups"],
    email: ["email", "email_verified"],
  },
  features: {
    devInteractions: { enabled: true },
  },
  async findAccount(_ctx, id) {
    return {
      accountId: id,
      async claims() {
        return {
          sub: id,
          name: "Compte de test Penpot",
          email,
          email_verified: true,
          groups,
        };
      },
    };
  },
});

provider.listen(Number(process.env.OIDC_TEST_PORT ?? 9000), process.env.OIDC_TEST_HOST ?? "127.0.0.1", () => {
  console.log(`IdP OIDC de test : ${issuer}/.well-known/openid-configuration`);
  console.log(`Client : ${clientId}`);
  console.log(`Redirect URI : ${redirectUri}`);
  console.log(`E-mail vérifié : ${email}`);
  console.log(`Groupes : ${groups.join(", ") || "aucun"}`);
  console.log("Usage local uniquement : l'écran de connexion accepte un nom et un mot de passe quelconques.");
});
