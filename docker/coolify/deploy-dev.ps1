<#
.SYNOPSIS
  Environnement de DEV du fork Penpot (enterprise-self-hosted) sur Coolify,
  avec un Keycloak de test pour le SSO d'organisation.

.DESCRIPTION
  .\docker\coolify\deploy-dev.ps1 push     Commit (fins de ligne corrigees) + push de la branche.
  .\docker\coolify\deploy-dev.ps1 deploy   Cree ou met a jour l'app Coolify via le CLI, puis la deploie.
  .\docker\coolify\deploy-dev.ps1 infos    URLs, identifiants de test et procedure SSO.

  Entre push et deploy : le workflow GitHub Actions construit les images (45 a 60 min),
  et le package GHCR "penpot-fork" doit etre rendu public une fois.
  Si PowerShell bloque le script : powershell -ExecutionPolicy Bypass -File .\docker\coolify\deploy-dev.ps1 push
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('push', 'deploy', 'infos')]
    [string] $Etape = 'push',

    [string] $PenpotUrl = 'https://penpot-dev.quentinthierry.fr',
    [string] $KeycloakUrl = 'https://sso-dev.quentinthierry.fr',
    [string] $TestEmail = 'sso-test@quentinthierry.fr',
    [string] $AppName = 'penpot-dev',
    [string] $ProjectUuid,
    [string] $ServerUuid,
    [string] $EnvironmentName = 'production',
    [string] $CommitMessage = ':sparkles: Add local admin console and organization SSO for self-hosting'
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$Branch = 'enterprise-self-hosted'
$GithubUser = 'Quentinthierry12'
$RepoUrl = "https://github.com/$GithubUser/penpot"
$ImageRepo = 'quentinthierry12/penpot-fork'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$PenpotUrl = $PenpotUrl.TrimEnd('/')
$KeycloakUrl = $KeycloakUrl.TrimEnd('/')

function Write-Step([string] $Text) {
    Write-Host ''
    Write-Host "==> $Text" -ForegroundColor Cyan
}

# --- git ---------------------------------------------------------------------

function Invoke-Git {
    & git -C $RepoRoot @args
    if ($LASTEXITCODE -ne 0) { throw "git $($args -join ' ') a échoué (code $LASTEXITCODE)." }
}

function Get-GitOutput {
    $out = & git -C $RepoRoot @args
    if ($LASTEXITCODE -ne 0) { throw "git $($args -join ' ') a échoué (code $LASTEXITCODE)." }
    return (($out | Out-String).Trim())
}

# --- CLI Coolify -------------------------------------------------------------

function Invoke-Coolify {
    $out = & coolify @args
    if ($LASTEXITCODE -ne 0) { throw "coolify $($args -join ' ') a échoué (code $LASTEXITCODE)." }
    return (($out | Out-String).Trim())
}

function Invoke-CoolifyJson {
    $text = Invoke-Coolify @args --format json
    $start = $text.IndexOfAny([char[]] '[{')
    if ($start -lt 0) { throw "Réponse inattendue du CLI : $text" }
    $data = $text.Substring($start) | ConvertFrom-Json
    if ($null -ne $data -and ($data.PSObject.Properties.Name -contains 'data')) { $data = $data.data }
    return $data
}

function Select-Item([object[]] $Items, [string] $Label) {
    $Items = @($Items | Where-Object { $null -ne $_ })
    if ($Items.Count -eq 0) { throw "Aucun $Label trouvé dans Coolify." }
    if ($Items.Count -eq 1) {
        Write-Host "$Label : $($Items[0].name) ($($Items[0].uuid))"
        return $Items[0]
    }
    Write-Host "Choisis le $Label :"
    for ($i = 0; $i -lt $Items.Count; $i++) {
        Write-Host ('  [{0}] {1} ({2})' -f ($i + 1), $Items[$i].name, $Items[$i].uuid)
    }
    do { $n = Read-Host 'Numéro' } until (($n -as [int]) -ge 1 -and ($n -as [int]) -le $Items.Count)
    return $Items[($n -as [int]) - 1]
}

function Find-App {
    return (@(Invoke-CoolifyJson app list) | Where-Object { $_.name -eq $AppName } | Select-Object -First 1)
}

# --- GHCR (lecture anonyme : le package doit être public) ----------------------

$script:GhcrToken = $null

function Test-GhcrTag([string] $Tag) {
    try {
        if (-not $script:GhcrToken) {
            $script:GhcrToken = (Invoke-RestMethod -Uri "https://ghcr.io/token?scope=repository:${ImageRepo}:pull" -TimeoutSec 30).token
        }
        $headers = @{
            Authorization = "Bearer $($script:GhcrToken)"
            Accept        = 'application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json'
        }
        Invoke-WebRequest -Uri "https://ghcr.io/v2/$ImageRepo/manifests/$Tag" -Method Head -Headers $headers -UseBasicParsing -TimeoutSec 30 | Out-Null
        return $true
    } catch {
        return $false
    }
}

function Find-ImageTag {
    # Commit le plus récent de la branche dont les trois images existent
    # (les commits qui ne touchent que la doc ou docker/coolify ne relancent pas le build).
    if (-not (Test-GhcrTag 'frontend-dev')) { return $null }
    $commits = (Get-GitOutput log --format=%H -n 30 HEAD) -split "\r?\n"
    foreach ($commit in $commits) {
        $short = $commit.Substring(0, 12)
        if ((Test-GhcrTag "frontend-$short") -and (Test-GhcrTag "backend-$short") -and (Test-GhcrTag "exporter-$short")) {
            return $short
        }
    }
    return $null
}

# --- Étapes --------------------------------------------------------------------

function Invoke-Push {
    Write-Step 'Branche'
    $current = Get-GitOutput rev-parse --abbrev-ref HEAD
    if ($current -ne $Branch) { throw "Branche courante « $current » : bascule d'abord sur « $Branch »." }

    Write-Step 'Fins de ligne : core.autocrlf=true (le dépôt garde des LF, seules les vraies modifications partent)'
    Invoke-Git config core.autocrlf true
    Invoke-Git add -A

    $staged = Get-GitOutput diff --cached --name-status
    if ($staged) {
        Write-Host $staged
        Invoke-Git --no-pager diff --cached --shortstat
        $answer = Read-Host "Commiter ces fichiers sur « $Branch » puis pousser sur GitHub (dépôt public) ? (o/N)"
        if ($answer -notmatch '^(o|oui|y|yes)$') {
            Write-Host 'Annulé : rien n''a été commité ni poussé (les fichiers restent indexés).'
            return
        }
        Invoke-Git commit -m $CommitMessage
    } else {
        Write-Host 'Rien de nouveau à commiter.'
    }

    Write-Step "Push de « $Branch »"
    Invoke-Git push -u origin $Branch

    Write-Step 'Suite'
    Write-Host "1. Première fois : active GitHub Actions sur ton fork : $RepoUrl/actions"
    Write-Host '   (bouton « I understand my workflows, go ahead and enable them »).'
    Write-Host '   Si Actions était désactivé pendant ce push, lance le workflow à la main :'
    Write-Host '   « Fork : images de dev (GHCR) » > Run workflow > branche enterprise-self-hosted.'
    Write-Host '2. Attends la fin du build (45 à 60 min).'
    Write-Host "3. Première fois : rends le package public : https://github.com/${GithubUser}?tab=packages"
    Write-Host '   > penpot-fork > Package settings > Change visibility > Public.'
    Write-Host "4. DNS chez OVH : enregistrements A vers ton VPS pour $(([Uri] $PenpotUrl).Host) et $(([Uri] $KeycloakUrl).Host)."
    Write-Host '5. Puis : .\docker\coolify\deploy-dev.ps1 deploy'
}

function Invoke-Deploy {
    Write-Step 'CLI Coolify'
    if (-not (Get-Command coolify -ErrorAction SilentlyContinue)) { throw "Le CLI « coolify » est introuvable dans le PATH." }
    Invoke-Coolify context verify | Out-Host

    Write-Step 'Images sur GHCR'
    Invoke-Git fetch --quiet origin $Branch
    $local = Get-GitOutput rev-parse HEAD
    $remote = Get-GitOutput rev-parse "origin/$Branch"
    if ($local -ne $remote) {
        Write-Warning "Ton HEAD local n'est pas celui de origin/$Branch : Coolify déploiera la version poussée."
    }
    $tag = Find-ImageTag
    if (-not $tag) {
        throw "Images introuvables sur ghcr.io/$ImageRepo : le build GitHub Actions n'est pas fini, a échoué, ou le package est encore privé."
    }
    Write-Host "Images : ghcr.io/${ImageRepo}:*-$tag"

    Write-Step 'DNS'
    foreach ($url in @($PenpotUrl, $KeycloakUrl)) {
        $hostName = ([Uri] $url).Host
        $ips = @()
        try {
            $ips = @(Resolve-DnsName -Name $hostName -Type A -ErrorAction Stop | Where-Object { $_.Type -eq 'A' } | ForEach-Object { $_.IPAddress })
        } catch { }
        if ($ips.Count -eq 0) {
            Write-Warning "$hostName ne résout pas : ajoute un enregistrement A vers ton VPS (sinon pas de certificat HTTPS)."
        } else {
            Write-Host "$hostName -> $($ips -join ', ')"
        }
    }

    Write-Step "Application Coolify « $AppName »"
    $domains = @('--compose-domain', "pdev-frontend=${PenpotUrl}:8080", '--compose-domain', "pdev-keycloak=${KeycloakUrl}:8080")
    $app = Find-App
    if ($app) {
        $uuid = $app.uuid
        Write-Host "Existe déjà : $uuid"
        Invoke-Coolify app update $uuid @domains | Out-Host
    } else {
        if (-not $ServerUuid) { $ServerUuid = (Select-Item @(Invoke-CoolifyJson server list) 'serveur').uuid }
        if (-not $ProjectUuid) { $ProjectUuid = (Select-Item @(Invoke-CoolifyJson project list) 'projet').uuid }
        $created = Invoke-CoolifyJson app create public `
            --server-uuid $ServerUuid --project-uuid $ProjectUuid --environment-name $EnvironmentName `
            --name $AppName --description 'Fork Penpot enterprise-self-hosted + Keycloak de test (SSO)' `
            --git-repository $RepoUrl --git-branch $Branch `
            --build-pack dockercompose --base-directory /docker/coolify --ports-exposes 8080 `
            --is-git-shallow-clone-enabled @domains
        $uuid = $created.uuid
        if (-not $uuid) { throw 'UUID de la nouvelle application absent de la réponse du CLI.' }
        Write-Host "Créée : $uuid"
    }

    Write-Step "Variables d'environnement"
    $envFile = [System.IO.Path]::GetTempFileName()
    try {
        @(
            "PENPOT_DEV_URL=$PenpotUrl",
            "KEYCLOAK_DEV_URL=$KeycloakUrl",
            "SSO_TEST_EMAIL=$TestEmail",
            "PENPOT_IMAGE_TAG=$tag"
        ) | Set-Content -Path $envFile -Encoding Ascii
        Invoke-Coolify app env sync $uuid --file $envFile | Out-Host
    } finally {
        Remove-Item -Path $envFile -ErrorAction SilentlyContinue
    }

    Write-Step 'Déploiement'
    Invoke-Coolify deploy uuid $uuid | Out-Host
    Write-Host "Logs : coolify app logs $uuid --service pdev-backend --follow"
    Write-Host '(premier démarrage : quelques minutes, le temps de tirer les images et de migrer la base)'

    Show-Infos $uuid
}

function Show-Infos([string] $Uuid) {
    if (-not $Uuid) {
        $app = Find-App
        if (-not $app) { throw "Application « $AppName » introuvable dans Coolify." }
        $Uuid = $app.uuid
    }

    $values = @{}
    try {
        foreach ($v in @(Invoke-CoolifyJson app env list $Uuid --show-sensitive)) {
            if ($null -eq $v) { continue }
            if (($v.PSObject.Properties.Name -contains 'is_preview') -and $v.is_preview) { continue }
            if ($v.key) { $values[[string] $v.key] = [string] $v.value }
        }
    } catch {
        Write-Warning 'Lecture des variables impossible via le CLI : utilise l''onglet Environment Variables de l''app dans Coolify.'
    }
    $secret = {
        param($Key)
        if ($values.ContainsKey($Key) -and $values[$Key]) { return $values[$Key] }
        return "(Coolify > $AppName > Environment Variables > $Key, généré au premier déploiement)"
    }

    Write-Step 'Accès'
    Write-Host "Penpot         : $PenpotUrl"
    Write-Host "Keycloak admin : $KeycloakUrl/admin   (admin / $(& $secret 'SERVICE_PASSWORD_KEYCLOAKADMIN'))"

    Write-Step 'Tester le SSO'
    Write-Host "1. Sur Penpot, crée un compte avec l'e-mail $TestEmail (mot de passe au choix, pas de vérification d'e-mail)."
    Write-Host "   Le SSO exige que l'e-mail renvoyé par Keycloak soit celui du compte Penpot."
    Write-Host '2. Dans la console d''administration (paramètres d''organisation), crée une organisation et configure son SSO :'
    Write-Host "     Issuer        : $KeycloakUrl/realms/penpot-dev"
    Write-Host '     Client ID     : penpot'
    Write-Host "     Client secret : $(& $secret 'SERVICE_PASSWORD_KCCLIENT')"
    Write-Host "     Callback      : $PenpotUrl/api/auth/oidc/callback  (déjà autorisé côté Keycloak)"
    Write-Host "3. Connexion SSO avec l'utilisateur Keycloak sso-test / $(& $secret 'SERVICE_PASSWORD_SSOTEST')"
    Write-Host ''
    Write-Host "Après le test : coolify app stop $Uuid   (libère 3 à 4 Go de RAM sur le VPS)"
}

switch ($Etape) {
    'push' { Invoke-Push }
    'deploy' { Invoke-Deploy }
    'infos' { Show-Infos '' }
}
