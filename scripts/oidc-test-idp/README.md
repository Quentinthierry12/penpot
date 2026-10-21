# Fournisseur OIDC local pour Penpot

Ce serveur de test utilise `oidc-provider` et son écran de connexion de développement. Il permet de tester la découverte OIDC, la redirection, le retour sur le callback et l'émission d'un ID Token signé, avec un e-mail vérifié et des groupes configurables.

Il est réservé aux essais locaux : le formulaire accepte n'importe quel nom et mot de passe. Ne l'exposez pas sur Internet et ne réutilisez pas ses identifiants en production.

## Démarrer

Depuis ce dossier :

```powershell
npm install
npm start
```

Valeurs par défaut :

- Issuer : `http://localhost:9000`
- Client ID : `penpot-local-test`
- Client secret : `local-only-test-secret`
- E-mail : `sso-test@example.test`
- Groupes : `club-rezo-asso_tech,role-asso_membre`
- Callback : `http://localhost:9001/api/auth/oidc/callback` (valeur par défaut du Compose Penpot)

Les valeurs peuvent être remplacées par les variables `OIDC_TEST_ISSUER`, `OIDC_TEST_CLIENT_ID`, `OIDC_TEST_CLIENT_SECRET`, `OIDC_TEST_EMAIL`, `OIDC_TEST_GROUPS`, `OIDC_TEST_REDIRECT_URI`, `OIDC_TEST_PORT` et `OIDC_TEST_HOST`.

## Groupes de test

Pour émettre les groupes d'administration :

```powershell
$env:OIDC_TEST_GROUPS = 'club-rezo-asso_tech,role-asso_bureau'
npm start
```

Pour tester un compte membre :

```powershell
$env:OIDC_TEST_GROUPS = 'club-rezo-asso_tech,role-asso_membre'
npm start
```

Le claim `groups` apparaît dans l'ID Token et dans UserInfo, avec le scope `profile`.

## Configuration dans Penpot

Configurer l'issuer, le client ID et le client secret ci-dessus. L'URI de callback enregistrée auprès du fournisseur doit correspondre exactement à l'URI publique de Penpot suivie de `/api/auth/oidc/callback`. Penpot et le navigateur doivent tous deux pouvoir joindre l'issuer. Si Penpot tourne dans Docker, `localhost` dans le conteneur désigne le conteneur lui-même ; utilisez une adresse de l'hôte accessible depuis le conteneur et depuis le navigateur.

Le SSO actuellement implémenté vérifie l'issuer, le client, la signature, la durée, le nonce et l'e-mail vérifié identique au compte Penpot existant. Il ne consomme pas encore le claim `groups` : les deux jeux de groupes ci-dessus servent à préparer et vérifier l'émission OIDC; l'attribution des rôles organisationnels devra être ajoutée côté Penpot avant que ces groupes changent les permissions.

Pour faire joindre l'IdP par un Penpot lancé dans Docker, `localhost` de l'IdP ne sera pas joignable depuis le conteneur. Utilisez un nom d'hôte résolu à la fois par le navigateur et par Docker, avec HTTPS et un certificat reconnu par les deux. L'exception HTTP volontairement limitée à `localhost` dans Penpot interdit d'utiliser `host.docker.internal` en HTTP pour l'issuer.
