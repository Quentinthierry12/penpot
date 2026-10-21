# Dev sur Coolify (fork enterprise-self-hosted)

Environnement **jetable** pour tester la console d'administration locale et le SSO
d'organisation, avec un Keycloak de test. Pas de vraies données ici.

## Contenu

- `docker-compose.yaml` : Penpot (frontend, backend, exporter, Postgres, Valkey) et
  Keycloak 26 avec un realm `penpot-dev` importé au démarrage (client `penpot`,
  utilisateur `sso-test`). Services préfixés `pdev-` pour éviter toute collision avec
  une autre instance Penpot du même Coolify.
- `deploy-dev.ps1` : commit et push de la branche, puis création et déploiement de
  l'application via le CLI Coolify.
- Images : `.github/workflows/fork-dev-images.yml` les construit sur GitHub Actions et
  les publie sur `ghcr.io/quentinthierry12/penpot-fork` (tags `frontend|backend|exporter-<sha12>`).

## Lancer

1. `.\docker\coolify\deploy-dev.ps1 push`
2. Première fois seulement : activer GitHub Actions sur le fork, attendre le build
   (45 à 60 min), puis rendre le package `penpot-fork` public.
3. DNS : enregistrements A `penpot-dev` et `sso-dev` vers le VPS.
4. `.\docker\coolify\deploy-dev.ps1 deploy` : choisit le serveur et le projet Coolify,
   crée l'application (ou la met à jour), règle les variables et déploie.
5. `.\docker\coolify\deploy-dev.ps1 infos` : URLs, secrets générés et procédure de test.

Paramètres utiles : `-PenpotUrl`, `-KeycloakUrl`, `-TestEmail`, `-AppName`,
`-ProjectUuid`, `-ServerUuid`.

## Tester le SSO

1. Créer un compte Penpot avec l'e-mail de test (`sso-test@quentinthierry.fr` par défaut).
   L'IdP doit renvoyer l'e-mail du compte Penpot, marqué vérifié.
2. Console d'administration : créer une organisation, puis configurer son SSO avec
   l'issuer `<KEYCLOAK_DEV_URL>/realms/penpot-dev`, le client `penpot` et le secret
   `SERVICE_PASSWORD_KCCLIENT` (variables d'environnement de l'app dans Coolify).
3. Se connecter via le SSO avec `sso-test` / `SERVICE_PASSWORD_SSOTEST`.

## À savoir

- Pas de SMTP : les e-mails (invitations, etc.) sont écrits dans les logs du backend.
- Keycloak tourne en `start-dev` avec une base éphémère : le realm est réimporté à chaque
  démarrage avec les mêmes secrets, et les changements faits à la main sont perdus.
- Compter 3 à 4 Go de RAM sur le VPS ; `coolify app stop <uuid>` après le test.
- Flags de dev (inscription ouverte, pas de vérification d'e-mail) : ne pas réutiliser
  tel quel en production.
