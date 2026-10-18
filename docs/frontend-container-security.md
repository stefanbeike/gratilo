# Frontend-Container: libexpat-Sicherheitskorrektur

## Ursache und Korrektur

Der Release-Scan blockierte wegen **CVE-2026-77214 (HIGH)** in
`libexpat 2.8.5-r0`. Auch das am 18.10.2026 frisch heruntergeladene offizielle
Stable-Image `nginx:1.30.6-alpine3.24` enthielt noch dieses Paket. Ein neuer
Image-Tag oder `--pull` allein behebt den Befund daher nicht.

Das Frontend verwendet nun ausdrücklich `nginx:1.30.6-alpine3.24`. Im
Runtime-Stage installiert `apk add --no-cache --upgrade 'libexpat>=2.9.0-r0'`
die korrigierte Version aus den vorhandenen offiziellen Alpine-3.24-Repositories.
Der Build schlägt fehl, falls diese Mindestversion nicht verfügbar ist.
Es werden weder Edge-Repositories noch fremde Paketquellen hinzugefügt.

Quellen:

- [Alpine-Sicherheitstracker: CVE-2026-77214](https://security.alpinelinux.org/vuln/CVE-2026-77214)
- [Offizielle nginx-Image-Tags](https://github.com/docker-library/official-images/blob/master/library/nginx)

Im wiederverwendbaren Workflow `005_docker_build.yml` erzwingt
`no-cache-filters: runtime` die erneute Ausführung der Runtime-Paketinstallation.
Der Node-Build-Cache bleibt nutzbar; das Frontend wird weiterhin nur einmal
gebaut. Die bestehenden Trivy-Gates für HIGH und CRITICAL, einschließlich
ungefixter Befunde und Secret-Scans, bleiben aktiv. Es gibt keine CVE-Ausnahme.

`USER nginx`, Port 80, SPA-Fallback und die beiden Backend-Proxys bleiben
erhalten. Der Docker-Healthcheck verwendet jetzt ausdrücklich `127.0.0.1`,
wie bereits die ECS-Vorlagen: Im Laufzeittest wurde `localhost` zuerst nach
IPv6 aufgelöst und abgewiesen, während nginx auf IPv4 Port 80 erreichbar war.
Stripe bleibt beim Release-Build deaktiviert.

## Lokal reproduzieren

```bash
docker build --pull --no-cache \
  --build-arg VITE_PAYMENTS_ENABLED=false \
  -t gratilo-security/frontend:expat-fix-local frontend

# Dieselbe Trivy-Version und dieselben Gates wie in GitHub Actions verwenden.
trivy image --scanners vuln,secret --severity HIGH,CRITICAL \
  --ignore-unfixed=false --exit-code 1 --timeout 15m \
  gratilo-security/frontend:expat-fix-local

docker run --rm --entrypoint sh gratilo-security/frontend:expat-fix-local \
  -c 'id; apk list --installed libexpat; nginx -v'
```

Die Standard-Einstiegspunkte ersetzen beim Start die Host-Platzhalter im
nginx-Template. Für einen eigenständigen lokalen nginx-Test beide
Service-Hosts auf `localhost` setzen; die gemeinsame Fargate-Task verwendet
ebenfalls `localhost`. Keine produktiven Zugangsdaten für diese Tests verwenden.

## Backend-Basisimages

Beide Backend-Dockerfiles verwenden weiterhin `eclipse-temurin:21-jre-jammy`
(Ubuntu 22.04), nicht Alpine. Ubuntu-Paketversionen enthalten gegebenenfalls
zurückportierte Fixes; die Alpine-Versionsgrenze darf darauf nicht übertragen
werden. Die aktuelle Runtime-Basis separat mit denselben Trivy-Gates prüfen.
`gradle:8.10-jdk21` und `node:22-alpine` sind ausschließlich Build-Stages;
deren Dateisysteme werden nicht in die Runtime-Images übernommen.

Die frisch geladene Temurin-Basis enthält `libexpat1 2.4.7-1ubuntu0.9`.
[Canonical führt diese Version ebenfalls als betroffen](https://github.com/canonical/ubuntu-security-notices/blob/main/osv/cve/2026/UBUNTU-CVE-2026-77214.json),
bewertet die CVE allerdings als **medium** und nennt dort noch keinen Fix für
Jammy. Ein erfolgreicher HIGH-/CRITICAL-Scan bedeutet daher ausdrücklich
nicht, dass diese Backend-Basis frei von allen Expat-Risiken ist. Den
Ubuntu-Sicherheitsfix weiter verfolgen; keine CVE-Ausnahme hinzufügen und
keine Alpine-Pakete in Ubuntu installieren.

## Validierung am 18.10.2026

- Frontend: 21 Tests in 4 Dateien erfolgreich; TypeScript-/Vite-Produktionsbuild
  erfolgreich, ohne Stripe-Schlüssel und mit deaktivierten Zahlungen.
- CI/CD: 68 Python- und 7 OIDC-Regressionstests erfolgreich; Actionlint erfolgreich.
- Docker: vollständiger erster Build mit `--pull --no-cache`; nach dem
  Healthcheck-Fix erneuter Build mit `--pull --no-cache-filter runtime` und
  Wiederverwendung des bereits geprüften Frontend-Build-Stages erfolgreich.
- Finale Runtime: nginx 1.30.6, Alpine 3.24.2, `libexpat 2.9.0-r0`, UID/GID 101.
- Isolierter Laufzeittest: `nginx -t`, Docker-Healthcheck, Startseite,
  `/login`, `/register`, Favicon, beide Proxys mit Ports und Query-Strings,
  Erhalt von Host und HTTPS-Header sowie HTTP 404 für interne E-Mail-Endpunkte
  erfolgreich. Die Upstreams waren lokale Mocks; keine externen API-Aufrufe.

- Trivy **0.76.0**, Scanner `vuln,secret`, `HIGH,CRITICAL`,
  `--ignore-unfixed=false --exit-code 1`: Das ursprüngliche nginx-Image
  reproduziert genau einen HIGH-Befund für `libexpat 2.8.5-r0`,
  CVE-2026-77214, Fix `2.9.0-r0` (Exit 1). Das finale Frontend besteht mit
  **0 HIGH, 0 CRITICAL und 0 Secret-Funden** (Exit 0).
- Frontend-Image für diese Prüfung:
  `sha256:4a56a6d995b1b18ab81eea13d0076a01625e72c627899cb096e2373add9c8130`.
- Die Datenbank wurde aus dem offiziellen OCI-Artefakt separat heruntergeladen
  und gegen dessen SHA256 geprüft; Datenstand **18.10.2026, 07:12:07 UTC**,
  nächste Aktualisierung 19.10.2026. Lokal verhindert `--skip-db-update`
  lediglich einen erneuten Download dieser bereits aktuellen Datenbank.
  In GitHub Actions bleiben automatische Datenbankupdates aktiv.
- Die frisch geladene gemeinsame Backend-Basis `eclipse-temurin:21-jre-jammy`
  besteht denselben vollständigen Scan ebenfalls mit **0 HIGH, 0 CRITICAL und
  0 Secret-Funden** (Exit 0). Image-Digest:
  `sha256:affcab5356e1270ac35d2361ee83135af2f03e8d580eca78a6d5d6fdbf048db0`.
  Die Java-Metadatenbank stammt vom 16.10.2026 und ist laut eigener
  Update-Metadaten bis 19.10.2026 aktuell. Der oben genannte offene
  Ubuntu-MEDIUM-Befund bleibt zu verfolgen. Die kompletten Backend-Anwendungen
  wurden für diese gezielte Frontend-Korrektur nicht neu gebaut; ihre
  bestehenden vollständigen Image-Scans laufen weiterhin im Release-Workflow.

Es wurden keine AWS-Ressourcen, Release-Tags oder veröffentlichten Images
verändert.
