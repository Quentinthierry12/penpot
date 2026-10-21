---
title: 1.3 Déployer le fork Penpot sur une VM Proxmox
desc: Guide de déploiement du fork Penpot sur Proxmox VE avec Docker Compose, HTTPS, SSO, sauvegardes et aperçu de l'installation sans Docker.
---

# Déployer ce fork Penpot sur Proxmox

Ce guide déploie **ce fork** de Penpot sur une machine virtuelle Debian hébergée par Proxmox VE. La voie recommandée utilise Docker Compose dans la VM. Une installation native sans Docker est techniquement possible, mais Penpot ne fournit pas de procédure manuelle maintenue et le système de build de ce dépôt s'appuie lui-même sur Docker.

> **Important — images du fork**
>
> Le fichier `docker/images/docker-compose.yaml` fourni ici utilise par défaut les images publiées par Penpot (`penpotapp/*`). Elles ne contiennent pas les changements de ce fork. Il faut construire des images locales depuis le dépôt, puis lancer Compose avec le tag de ces images. Un simple `docker compose pull` déploierait l'amont Penpot, sans la console d'administration locale ni les fonctions SSO de ce fork.

Pour le SSO de ce fork, l'URL publique doit être stable et en HTTPS. L'issuer OIDC doit être joignable depuis le backend Penpot et par le navigateur. Le callback est `<URL publique Penpot>/api/auth/oidc/callback`. Le claim de groupes au format `slug-asso_ROLE` est en préparation; à la date de ce guide, les groupes IdP ne changent pas encore les rôles Penpot.

## 1. Préparer la VM dans Proxmox VE

Dans l'interface Proxmox, créez une VM KVM, plutôt qu'un conteneur LXC, pour isoler Penpot et son moteur de conteneurs.

Configuration de départ pour une petite équipe :

| Ressource | Valeur de départ |
| --- | --- |
| Système | Debian 13 amd64, installation minimale |
| Processeur | 4 vCPU |
| Mémoire | 8 Gio; augmentez-la si les exports et le nombre d'utilisateurs augmentent |
| Disque système | 40 à 60 Gio |
| Données | espace supplémentaire selon les fichiers Penpot et la politique de sauvegarde |
| Réseau | carte VirtIO sur `vmbr0`, adresse DHCP réservée ou IP fixe |
| Agent invité | activer l'option QEMU Guest Agent dans Proxmox |

Dans Debian, installez l'agent et les outils de base :

```bash
sudo apt update
sudo apt install -y qemu-guest-agent ca-certificates curl git gnupg
sudo systemctl enable --now qemu-guest-agent
```

Configurez un nom DNS comme `penpot.example.org` vers l'adresse de la VM. Sur le routeur ou le pare-feu, ne publiez que TCP 80 et 443 vers la VM si elle doit être accessible depuis Internet. Restreignez SSH à votre réseau d'administration. Ne publiez pas l'interface Proxmox (port 8006), PostgreSQL, Valkey, ni le port interne de Penpot sur Internet.

La configuration réseau de Proxmox utilise un bridge Linux (par exemple `vmbr0`) pour connecter les VM au réseau physique; consultez le [guide réseau Proxmox](https://pve.proxmox.com/wiki/Network_Configuration). Planifiez des sauvegardes Proxmox vers un stockage distinct de celui de la VM; Proxmox VE fournit `vzdump` et l'intégration Proxmox Backup Server ([guide d'administration](https://pve.proxmox.com/pve-docs/pve-admin-guide.pdf)). Une image ou un snapshot de VM ne remplace pas une sauvegarde applicative de PostgreSQL et des fichiers.

## 2. Installer Docker Engine et Compose

Installez Docker depuis son dépôt officiel pour Debian. Les commandes ci-dessous suivent la méthode APT officielle :

```bash
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/debian
Suites: $(. /etc/os-release && echo "$VERSION_CODENAME")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo docker run --rm hello-world
sudo docker compose version
```

Pour plus de détails ou une autre version de Debian, utilisez la [documentation officielle d'installation Docker sur Debian](https://docs.docker.com/engine/install/debian/). Sur les systèmes qui utilisent UFW ou firewalld, Docker avertit que les ports publiés par les conteneurs peuvent contourner les règles habituelles du pare-feu; n'exposez que le proxy HTTPS et utilisez un pare-feu adapté à Docker ([notes de sécurité Docker](https://docs.docker.com/engine/install/debian/#firewall-limitations)).

## 3. Récupérer le fork et construire ses images

Clonez le dépôt et utilisez la branche ou le commit qui contient les changements du fork :

```bash
git clone https://github.com/Quentinthierry12/penpot.git
cd penpot
git switch enterprise-self-hosted
git branch --show-current
git log -1 --oneline
```

Assurez-vous que le commit est bien celui que vous souhaitez déployer. Les changements présents uniquement dans le poste de développement, mais pas encore commités et poussés sur GitHub, ne seront pas inclus dans un nouveau clone.

Le script `manage.sh` compile les composants dans l'environnement de build Penpot, puis les emballe dans les images. Construisez les bundles utilisés par le Compose :

```bash
./manage.sh pull-devenv
./manage.sh build-frontend-bundle
./manage.sh build-backend-bundle
./manage.sh build-exporter-bundle
./manage.sh build-mcp-bundle
```

Construisez ensuite les images locales avec un tag distinctif :

```bash
./manage.sh build-frontend-docker-image --tag local
./manage.sh build-backend-docker-image --tag local
./manage.sh build-exporter-docker-image --tag local
./manage.sh build-mcp-docker-image --tag local
```

`manage.sh` utilise Docker pour son environnement de build, même si le processus de build est lancé depuis la VM. Il faut donc que le disque ait assez d'espace pour les images et bundles, et que la VM puisse télécharger les dépendances. Les images applicatives locales portent les noms `penpotapp/frontend:local`, `penpotapp/backend:local`, `penpotapp/exporter:local` et `penpotapp/mcp:local`.

## 4. Préparer Compose pour la production

Le fichier de Compose se trouve dans `docker/images`. Avant le premier démarrage :

1. Dans l'ancre `x-uri`, remplacez `http://localhost:9001` par l'URL HTTPS exacte, par exemple `https://penpot.example.org`.
2. Dans `x-flags`, retirez `disable-secure-session-cookies` et `disable-email-verification`. Conservez `enable-admin-console` pour cette implémentation locale. Configurez un vrai serveur SMTP au lieu du Mailpit de démonstration. Ne laissez pas le serveur de développement PREPL accessible à Internet.
3. Dans `x-secret-key`, remplacez la clé de démonstration par une variable d'environnement obligatoire :

   ```yaml
   PENPOT_SECRET_KEY: ${PENPOT_SECRET_KEY:?Définir PENPOT_SECRET_KEY dans .env}
   ```

4. Remplacez le mot de passe PostgreSQL de démonstration par la même variable dans les deux endroits : l'ancre `x-database` (`PENPOT_DATABASE_PASSWORD`) et le service `penpot-postgres` (`POSTGRES_PASSWORD`). Utilisez `${PENPOT_DATABASE_PASSWORD:?Définir PENPOT_DATABASE_PASSWORD dans .env}`.
5. Dans `penpot-frontend.ports`, liez le port uniquement à l'interface locale de la VM :

   ```yaml
   - "127.0.0.1:9001:8080"
   ```

6. Remplacez les paramètres SMTP `penpot-mailpit` par ceux de votre fournisseur de courriel et gardez `enable-smtp` dans les flags.

Créez ensuite `docker/images/.env` (ce fichier ne doit pas être ajouté à Git) :

```dotenv
PENPOT_VERSION=local
PENPOT_SECRET_KEY=REMPLACER_PAR_UNE_CLE_ALEATOIRE_LONGUE
PENPOT_DATABASE_PASSWORD=REMPLACER_PAR_UN_AUTRE_SECRET_LONG
```

Générez les valeurs plutôt que de conserver ces exemples :

```bash
python3 -c 'import secrets; print(secrets.token_urlsafe(64))'
```

Gardez une copie chiffrée de `PENPOT_SECRET_KEY` dans votre gestionnaire de secrets. Cette clé protège des secrets enregistrés par l'application; en perdre la valeur empêche de relire les secrets SSO chiffrés. Ne la changez pas après déploiement sans suivre une procédure de rotation.

Le Compose de ce dépôt utilise PostgreSQL 15 et un volume explicitement nommé pour ses données. Gardez cette version pour ce déploiement initial et ne changez pas de version majeure PostgreSQL sans plan de migration et sauvegarde vérifiée. Le guide de configuration Penpot recommande une version récente de PostgreSQL; la mise à niveau d'une base existante est une opération séparée.

## 5. Configurer HTTPS avec Caddy

Installez Caddy selon sa [documentation officielle](https://caddyserver.com/docs/install). Si le DNS du domaine pointe vers la VM et que TCP 80/443 y arrive, le fichier `/etc/caddy/Caddyfile` peut contenir :

```caddyfile
penpot.example.org {
    reverse_proxy 127.0.0.1:9001
}
```

Validez et rechargez Caddy :

```bash
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

Caddy gère automatiquement le certificat TLS pour un nom de domaine public joignable ([HTTPS automatique](https://caddyserver.com/docs/automatic-https)). Si votre reverse proxy est déjà ailleurs, configurez-le vers le port local `9001` de la VM et faites pointer `PENPOT_PUBLIC_URI` vers l'URL HTTPS finale. Le proxy doit transmettre les WebSockets et les uploads volumineux; consultez aussi la [configuration du proxy Penpot](./docker/#configure-the-proxy-and-https).

## 6. Démarrer et vérifier Penpot

Depuis `docker/images` :

```bash
cd docker/images
sudo docker compose -p penpot config -q
sudo docker compose -p penpot up -d
sudo docker compose -p penpot ps
sudo docker compose -p penpot logs -f penpot-backend
```

Ouvrez `https://penpot.example.org`. Vérifiez que la page de connexion se charge, créez ou invitez les comptes initiaux et contrôlez qu'un fichier peut être créé puis exporté. Dans les paramètres d'organisation, vérifiez que la console locale est visible.

Configurez ensuite le SSO OIDC pour une organisation dans la console : issuer HTTPS (identifiant d'émetteur, sans `/.well-known/openid-configuration`), client ID, secret client, et URI de callback exacte `<PENPOT_PUBLIC_URI>api/auth/oidc/callback`. L'IdP doit renvoyer l'adresse courriel déjà liée au compte Penpot et la déclarer vérifiée. Le fournisseur npm de test est dans `scripts/oidc-test-idp`; il n'est destiné qu'aux essais. Les groupes `slug-asso_ROLE`, `role-asso_bureau` et `role-asso_membre` ne sont pas encore appliqués aux rôles Penpot.

Consultez les journaux et l'état des services :

```bash
sudo docker compose -p penpot ps
sudo docker compose -p penpot logs --tail=200 penpot-backend penpot-frontend penpot-postgres penpot-valkey
```

## 7. Mise à jour et sauvegardes

Pour mettre à jour le fork, récupérez d'abord le commit voulu, reconstruisez les bundles et les quatre images avec le même tag `local`, puis redémarrez Compose :

```bash
git fetch origin
git switch enterprise-self-hosted
git pull --ff-only
./manage.sh build-frontend-bundle
./manage.sh build-backend-bundle
./manage.sh build-exporter-bundle
./manage.sh build-mcp-bundle
./manage.sh build-frontend-docker-image --tag local
./manage.sh build-backend-docker-image --tag local
./manage.sh build-exporter-docker-image --tag local
./manage.sh build-mcp-docker-image --tag local
cd docker/images
sudo docker compose -p penpot up -d
```

Après chaque mise à jour, vérifiez les logs et les parcours de connexion/SSO. Gardez les mises à jour du fork et les mises à jour majeures de PostgreSQL séparées. N'exécutez pas `docker compose down -v` : l'option `-v` supprime les volumes de données.

Faites des sauvegardes régulières de **la base PostgreSQL et des assets** vers un emplacement distinct de la VM. Exemple pour la base (adaptez le dossier de sauvegarde) :

```bash
sudo install -d -m 0700 /srv/backups/penpot
sudo docker compose -p penpot exec -T penpot-postgres \
  pg_dump -U penpot -d penpot -Fc \
  > "/srv/backups/penpot/database-$(date +%F).dump"
```

Sauvegardez également le volume Docker `penpot_assets` (son nom réel peut être préfixé par le projet `penpot`; contrôlez-le avec `docker volume ls`) et la clé `PENPOT_SECRET_KEY`. Complétez ces sauvegardes par une sauvegarde de la VM dans Proxmox ou Proxmox Backup Server. Faites périodiquement un test de restauration sur une VM isolée; une sauvegarde qui n'a pas été restaurée au moins une fois n'est pas validée.

## Installation sans Docker

Cette voie est réservée aux personnes capables d'administrer séparément les services Linux. La documentation Penpot classe l'installation manuelle parmi les options communautaires non prises en charge, et indique de partir de l'architecture et des fichiers de configuration Docker. Dans ce dépôt, `manage.sh build` lance aussi les builds dans le conteneur d'environnement de développement.

Une installation native doit au minimum fournir et maintenir :

- PostgreSQL et ses migrations, avec comptes et sauvegardes;
- Valkey (compatible Redis) pour les notifications et les tâches;
- le backend Clojure/JVM et sa configuration d'environnement;
- les fichiers du frontend servis par Nginx;
- l'exporter et son runtime Node/Chromium;
- le serveur MCP si activé;
- les outils de build du dépôt (Clojure CLI, Babashka, Node/pnpm, Rust/Emscripten et dépendances système) aux versions compatibles avec le commit déployé.

Les scripts de modules se trouvent dans `backend/scripts/build`, `frontend/scripts/build`, `exporter/scripts/build` et `mcp/scripts/build`. Ils produisent des artefacts, mais le dépôt ne fournit pas les unités systemd, les configurations Nginx natives ni une procédure testée de migration et de mise à jour sans conteneurs. Il faut donc concevoir les unités et la configuration des services à partir des Dockerfiles et de `docker/images/files/`, puis valider le tout en préproduction avant d'y mettre des données réelles.

Pour éviter Docker sur la VM de production tout en gardant le processus de build du projet, une option consiste à construire les bundles/images dans une VM de build isolée, puis à empaqueter et installer les composants natifs sur le serveur. Cela ne transforme pas pour autant l'installation native en option prise en charge. Pour une installation reproductible de ce fork, le Compose dans une VM Proxmox dédiée est la voie documentée ici.
