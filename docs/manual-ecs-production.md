# Gratilo: manueller ECS-Fargate-Betrieb ohne Zahlungen

## Prüfstand und Grenzen

Geprüft und korrigiert auf `feat/gratilo-ecs-production-pipeline`; ursprüngliche
Basis: Remote-main `2f98e6c` (`stefanbeike/gratilo`), separat bearbeitet, ohne
AWS-Aufrufe, Secret-Abfragen, Terraform/Crossplane oder Workflow-Ausführung.
Die Website ist `https://gratilo.eu`; Stripe bleibt ausgeschaltet.
Zahlungen sind standardmäßig vollständig deaktiviert (`PAYMENTS_ENABLED=false`),
auch simulierte Zahlungen. Für den ECR-Release werden keine Stripe-Keys benötigt.

## Sämtliche Anwendungsvariablen und Geheimnisse

U = user-service, P = payment-service, F = frontend. ECS injiziert Secrets als
Umgebungsvariablen; die Anwendungen lesen Secrets Manager nicht selbst.

| Variable | Container | Geheim? | Herkunft / Wert |
|---|---|---|---|
| `DATASOURCES_DEFAULT_USERNAME` | U, P | Zugangsdaten | `gratilo/prd/database`, JSON `username` |
| `DATASOURCES_DEFAULT_PASSWORD` | U, P | ja | `gratilo/prd/database`, JSON `password` |
| `JWT_SECRET` | U, P | ja | `gratilo/prd/jwt`, JSON `secret`, identische Version in beiden Containern |
| `INTERNAL_API_KEY` | U, P | ja | vorgesehen: `gratilo/prd/internal-api`, JSON `secret`; Existenz unbestätigt |
| `JAVAMAIL_AUTHENTICATION_USERNAME` | U | Zugangsdaten | `gratilo/prd/smtp`, JSON `username` |
| `JAVAMAIL_AUTHENTICATION_PASSWORD` | U | ja | `gratilo/prd/smtp`, JSON `password` |
| `STRIPE_SECRET_KEY` | P | ja, nur bei aktivierten Zahlungen | noch unbestätigtes Stripe-Test-Secret, vorgeschlagener JSON-Key `secret_key`; ausschließlich `sk_test_…` |
| `STRIPE_WEBHOOK_SECRET` | P | ja, nur bei aktivierten Zahlungen | noch unbestätigtes Stripe-Test-Secret, vorgeschlagener JSON-Key `webhook_secret`; `whsec_…` vom Test-Webhook |
| `VITE_PAYMENTS_ENABLED` | F, nur Build | nein | `false`, kein Stripe-Key nötig |
| `PAYMENTS_ENABLED` | P | nein | `false`, alle Zahlungen und Stripe-Aufrufe gesperrt |
| `USER_SERVICE_HOST` | F | nein | `localhost` in ECS, `user-service` in Compose |
| `PAYMENT_SERVICE_HOST` | F | nein | `localhost` in ECS, `payment-service` in Compose |
| `MICRONAUT_ENVIRONMENTS` | U, P | nein | zwingend `prd` in ECS |
| `DATASOURCES_DEFAULT_URL` | U, P | nein | JDBC-URL unten, niemals Passwort/User in URL |
| `FRONTEND_BASE_URL` | U, P | nein | `https://gratilo.eu` |
| `FEATURE_TIP_POOLS_ENABLED` | U, P | nein | `false`, Pool-Splitting deaktiviert |
| `JAVA_TOOL_OPTIONS` | U, P | nein | `-XX:MaxRAMPercentage=65.0 -XX:+ExitOnOutOfMemoryError` |
| `MAIL_HOST` | U | nein | `email-smtp.eu-central-1.amazonaws.com` |
| `MAIL_PORT` | U | nein | `587` |
| `MAIL_FROM_EMAIL` | U | nein | `noreply@gratilo.eu` für System-E-Mails |
| `MAIL_GENERAL_FROM_EMAIL` | U | nein | `info@gratilo.eu` für allgemeinen Versand |
| `MAIL_FROM_NAME`, `APP_NAME` | U | nein | `Gratilo` |
| `JWT_EXPIRATION_MINUTES` | U | nein | optional, Default `60` |
| `USER_SERVICE_URL` | P | nein | `http://localhost:8081` |
| `STRIPE_SUCCESS_URL` | P | nein | `https://gratilo.eu/pay/success?session_id={CHECKOUT_SESSION_ID}` |
| `STRIPE_CANCEL_URL` | P | nein | `https://gratilo.eu/pay/cancelled` |
| `STRIPE_TEST_ONLY` | P | nein | `true`, im ECS-Profil zwingend |
| `STRIPE_SANDBOX_PAYOUTS` | P | nein | bei genehmigtem Testbetrieb zwingend `true`; nur simulierte Auszahlungen |
| `STRIPE_DEMO_ENABLED` | P | nein | `false`, kein anonymer Demo-Parameter zum Verbuchen fingierter Tipps |
| `STRIPE_FEE_PERCENT` | P | nein | optional, Default `1.5` |
| `STRIPE_FEE_FIXED_MINOR` | P | nein | optional, Default `25` |

Keine `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, S3- oder MinIO-Zugangsdaten in
ECS nötig. Keine weiteren externen API-Anbieter wurden im Anwendungscode gefunden.

### Expliziter Stripe-Test-Rollout

Der Release-Workflow bleibt standardmäßig zahlungsfrei. Ein freigegebener Test-Rollout
erfordert `stripe_test_mode=true`, zusätzlich die Bestätigung
`ENABLE STRIPE TEST PAYMENTS`, die normale Release-Bestätigung sowie weiterhin die
Freigabe der GitHub-Umgebung `production`. Der Build erhält `pk_test_…` aus der
GitHub-Variable `STRIPE_TEST_PUBLISHABLE_KEY`; Backend-Schlüssel und Webhook-Secret
bleiben ECS-Secrets-Manager-Referenzen. Die Deployment-Prüfung akzeptiert Zahlungen
nur mit `sk_test_…` (wird beim Payment-Service-Start erzwungen), aktivierter
Webhook-Signaturprüfung, privatem Activation-Code, leerer UUID-Allowlist,
`STRIPE_SANDBOX_PAYOUTS=true`, `STRIPE_DEMO_ENABLED=false` und deaktiviertem Pooling.

`STRIPE_SANDBOX_PAYOUTS=true` ist der sichere Pfad: Auszahlungsaktionen werden als
simulierte Gratilo-Test-Ledger-Einträge erfasst und lösen keinen Stripe-`/v1/payouts`
Aufruf aus. Der Wert `false` würde den externen Stripe-Payout-Pfad aktivieren und ist
für Test-Rollouts daher verboten. Der normale Release-Modus verlangt weiterhin
`PAYMENTS_ENABLED=false` und `STRIPE_SANDBOX_PAYOUTS=false`.
Stripe-Onboarding-Client-Secrets werden pro Anfrage von Stripe erzeugt und gezielt
an den angemeldeten Benutzer gegeben; sie sind keine ECS-Konfigurationswerte.
Benutzerpasswörter werden gehasht in PostgreSQL gespeichert. E-Mail-Verifikations-,
Passwort-Reset- und Pool-Einladungstokens sind dynamische Anwendungsdaten.

### Datenbank und SMTP

```text
jdbc:postgresql://<RDS_ENDPOINT>:5432/gratilodb?sslmode=verify-full&sslrootcert=/app/rds-ca-bundle.pem
```

Beide Images enthalten das offizielle Frankfurt-RDS-CA-Bundle. Benötigte Schemas:
`user_mgmt` und `payment_mgmt`, inklusive DDL-Rechten für Liquibase. Historische
Changesets bleiben unverändert; Migration 014 korrigiert `.com`/`.de` zu `.eu`.
Vor erstem Start Backup und Schema-Berechtigungen prüfen. Ein Image-Rollback macht
Datenmigrationen nicht rückgängig.

SES verwendet in `prd` verpflichtend SMTP-Authentifizierung, STARTTLS, Port 587,
Serverzertifikatsprüfung und deaktiviertes JavaMail-Debugging. Die Credentials sind
SES-SMTP-Zugangsdaten, keine IAM-Access-Keys für GitHub. Alte
`JAVAMAIL_PROPERTIES_*`-/`MAIL_SMTP_*`-Overrides nicht in die Task übernehmen.

### Profilbilder / S3 / MinIO

Der Browser erzeugt ein 256×256-JPEG als Data-URL. Der User-Service prüft Typ,
Abmessungen und Größe; `user_mgmt.users.profile_image` speichert es als TEXT.
Es gibt keinen S3-/MinIO-Client und keine Upload-URL-Signierung im aktuellen Code.
Die bisherigen `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_BUCKET` in Compose
waren wirkungslos und wurden aus den Anwendungscontainern entfernt. MinIO bleibt
als optionale lokale Alt-Infrastruktur; `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD`
gehören ausschließlich dorthin, nicht in die ECS-Task. S3-Speicherung wäre eine
separate Architekturänderung und ist hier nicht eingeführt.

### Lokale Entwicklungswerte und CI

Lokale `application.yml`-Defaults und Compose-Testwerte sind ausdrücklich keine
Produktionszugangsdaten. `prd` verlangt externe DB-, JWT-, interne und SMTP-Secrets.
Compose setzt denselben JWT-/internen Wert in beiden Backends und verwendet
`http://user-service:8081` statt Container-localhost. Reale lokale Werte nur in die
ignorierte `.env` oder Shell. Der ECR-Release benötigt weder `STRIPE_PUBLISHABLE_KEY` noch Stripe-Secrets.

Weitere CI-Werte: `GITHUB_TOKEN` automatisch; optional `SONAR_TOKEN` (geheim),
`SONAR_HOST_URL` (URL), `SONAR_PROJECT_KEY`, `SONAR_ORGANIZATION` (Konfiguration).
Keine Anwendungs-Secrets in GitHub Actions notwendig. `AWS_ACCOUNT_ID`,
`AWS_REGION`, Rollen-/Cluster-/Service-/Task-Namen sind keine Geheimnisse.

## Interne Authentifizierung und JWT

`POST /user-service/internal/notifications/email` und `/email/general` prüfen
`X-Internal-Secret` vor jedem Versand. Fehlende/leere/falsche Header ergeben 401;
auch ein leer konfigurierter Schlüssel autorisiert niemals. Die gleiche
zeitkonstante Prüfung schützt `GET /user-service/internal/pools/{ownerId}`,
falls das Pool-Feature aktiviert wird. Der Pool-Endpunkt ist bei deaktiviertem
Feature nicht registriert. Nginx veröffentlicht `/user-service/internal/` nicht
(404); Container kommunizieren direkt über localhost:8081. Ports 8081/8082 nicht
am ALB veröffentlichen und nicht für externe Security Groups öffnen.

Der Payment-Service verwendet den internen Schlüssel derzeit beim Pool-Abruf;
ein automatischer E-Mail-Aufruf aus dem Payment-Service ist nicht implementiert.
JWT-Signierung ist explizit HS256, Validierung in beiden Backends ebenfalls.
Der gleiche Secret-Wert muss ungeändert in beide Container injiziert werden;
mindestens 32 zufällige Bytes verwenden. Kein Token/Secret wird zu Testzwecken
protokolliert. Nach Secret-Rotation beide Container gemeinsam neu starten.

## Zahlungen ausgeschaltet; Stripe nicht erforderlich

`PAYMENTS_ENABLED=false` ist der Backend-Default. Die Request-Sperre blockiert
Checkout/Bestätigung, Onboarding/Sync, Webhooks, Auszahlungen und alle Ledger-
Änderungen mit HTTP 503 / `PAYMENTS_DISABLED`, bevor Controller ausgeführt werden.
Nur gespeicherte Transaktionen und der gespeicherte Connected-Account-Status sind
lesbar. Healthchecks funktionieren weiter. Es gibt keine Hintergrund-Stripe-Jobs.
Frontend-Builds des Release-Workflows setzen `VITE_PAYMENTS_ENABLED=false` und
übergeben keinen Stripe-Key; Zahlungsseiten und Zahlungsaktionen sind gesperrt.
Die Task-Vorlage enthält keine Stripe-Secret-Referenzen mehr. Keine erfundenen
API-Schlüssel einsetzen. Ein späterer Stripe-Testbetrieb erfordert eine separat
reviewte Aktivierung samt echten Test-Keys und der vorhandenen Sandbox-Validierung.

## Manuell verwendbare Task-Vorlage

Datei: `deployment/ecs/gratilo-prd-app.task-definition.json`.

Fest eingetragen: Account `070351676632`, Frankfurt, Familie `gratilo-prd-app`,
Linux/X86_64, awsvpc/Fargate, 1024 CPU, 3072 MiB und die vorgegebenen Rollen.
Cluster `gratilo-prd-cluster` gehört zur Service-Konfiguration, nicht in das
Task-Definition-JSON.

Vor dem Einfügen in den JSON-Editor der AWS-Konsole ersetzen:

1. `<RELEASE_TAG>` durch denselben tatsächlich vorhandenen geprüften Image-Tag
   aller drei ECR-Repositories, alternativ Image-Digests pro Container.
2. `<RDS_ENDPOINT>` durch den privaten tatsächlichen RDS-DNS-Namen.
3. `<CLOUDWATCH_LOG_GROUP>` durch eine manuell angelegte Loggruppe.
4. Jedes `<ACTUAL_SUFFIX>` durch das jeweilige tatsächliche Secrets-Manager-ARN-
   Suffix. Es ist pro Secret verschieden. Nur den ARN in der Konsole kopieren,
   keine Secret-Werte auslesen oder in diese Datei schreiben.


Diese Werte sind unbekannt; deshalb ist die versionierte Datei eine gültige
JSON-Vorlage, aber vor Ersetzung der Marker noch nicht registrierbar. Keine
Suffixe oder Loggruppen wurden erfunden oder angelegt.

| Container | Port | CPU | RAM | Healthcheck |
|---|---|---:|---:|---|
| frontend | 80 | 128 | 256 MiB | `GET http://localhost/` |
| user-service | 8081 | 448 | 1280 MiB | `GET http://localhost:8081/health` |
| payment-service | 8082 | 448 | 1280 MiB | `GET http://localhost:8082/health` |

`/api/status` existiert ebenfalls in beiden Services, prüft aber nur statischen
Status; `/health` ist für ECS vorgesehen. Frontend wartet auf beide gesunden
Backends. `awslogs` für alle Container; keine automatische Loggruppenerstellung.
Nginx läuft ohne root und benötigt den enthaltenen sysctl für Port 80.
Fargate Linux Platform 1.4.0+ verwenden (JSON-Key-Secrets und sysctl-Unterstützung).

## IAM und manuelle AWS-Schritte

Execution Role: `gratilo-prd-ecs-execution-role`. Die Vorlage
`deployment/iam/gratilo-prd-execution-policy.json` erlaubt ECR-Pull, CloudWatch-
Log-Streams und `secretsmanager:GetSecretValue` für database, smtp, jwt und
internal-api mit jeweils genau sechs Suffix-Zeichen (`??????`). Stripe-Secrets sind bei ausgeschalteten Zahlungen nicht erforderlich. Für maximal exakte Bindung alle Secret-ARNs vollständig einsetzen.
IAM-Resource-ARNs enthalten keine JSON-Key-Selektoren. Bei kundeneigenen KMS-Keys
zusätzlich `kms:Decrypt` auf genau diese Keys samt passender Key Policy erlauben.

Task Role: `gratilo-prd-ecs-task-role`. Aktuell keine S3-, SES-API- oder Secret-
Leserechte erforderlich: ECS injiziert Secrets über die Execution Role, SES
verwendet SMTP. Beide ECS-Rollen brauchen den Trust-Service `ecs-tasks.amazonaws.com`.

Manuell in der Konsole prüfen/einrichten:

- Existenz/Rechte von `gratilo/prd/internal-api` und JWT;
  bestehende DB-/SMTP-Secrets und Schlüsselstruktur bestätigen.
- Rollen samt Execution Policy, optionale KMS-Rechte, CloudWatch-Loggruppe.
- RDS PostgreSQL 15 / gratilodb, Schemas, private SG-Verbindung auf 5432, Backups.
- ECR-Images und geprüfte unveränderliche Tags; keine Stripe-Konfiguration nötig.
- Cluster `gratilo-prd-cluster`, gemeinsame Task, Service/ALB-Zuordnung frontend:80,
  Target-Typ ip, HTTPS/ACM, DNS gratilo.eu und HTTP-zu-HTTPS-Weiterleitung.
- Service-Circuit-Breaker mit Rollback und Healthcheck Grace Period vorsehen.
- Private Subnetze: ECR api/dkr, S3-Gateway (Image-Layer), CloudWatch Logs und
  Secrets Manager benötigen erreichbare VPC-Endpunkte oder passenden Egress.
  SES-SMTP benötigt erreichbaren Port 587, ggf. SES-SMTP-VPC-Endpunkt.
  Ein später aktivierter Stripe-Betrieb benötigt zusätzlich NAT/Proxy oder eine bewusst gewählte Alternative.
- SES-Absender/DKIM/DMARC prüfen. Stripe bleibt deaktiviert. Keine ungefragten
  Test-Mails oder Zahlungen auslösen. Service-Start erst nach ausdrücklicher Freigabe.

## GitHub OIDC und Workflows

Vorgegebene Rolle: `arn:aws:iam::070351676632:role/gratilo-github-actions-role`.

Ohne GitHub Environment lautet der main-Subject:
`repo:stefanbeike@13099702/gratilo@1351208624:ref:refs/heads/main`.
Mit `environment: production` lautet er dagegen:
`repo:stefanbeike@13099702/gratilo@1351208624:environment:production`.
Die Trust-Vorlagen stehen in `deployment/iam/github-oidc-*-trust.json`; für die
gemeinsame Rolle ist `github-oidc-trust-policy.json` die kombinierte Variante.
Audience immer `sts.amazonaws.com`; keinen Wildcard-Repo-Subject verwenden.
Der Gesamtworkflow verwendet zusätzlich den entsprechenden Subject mit
`environment:release` für ECR. Beide Environments nur auf main beschränken und
für production Required reviewers und Prevent self-review erzwingen. Ein reiner main-Trust akzeptiert diesen
Environment-Subject nicht. Keine OIDC-Tokens für PR-Builds freigeben.

AWS-Authentifizierung darf nur über `aws-actions/configure-aws-credentials`
mit `id-token: write` im tatsächlich AWS nutzenden Job erfolgen; keine permanenten
AWS-Schlüssel oder geerbte CLI-Profile. Die GitHub-Rolle benötigt keine
`secretsmanager:GetSecretValue`-Rechte für Anwendungs-Secrets.

## Quellen

- [ECS-Secrets und JSON-Key-Referenzen](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/secrets-envvar-secrets-manager.html)
- [ECS Execution Role / KMS](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_execution_IAM_role.html)
- [GitHub OIDC mit AWS](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws)

Der ECR-Publish nutzt `release`, der anschließende Deploy das geschützte Environment
`production`. Der manuelle Gesamtworkflow mit unabhängiger Deploy-Freigabe ist in
[cicd.md](cicd.md) einschließlich IAM-Rechten und Rollback beschrieben.
Infrastruktur wird weiterhin manuell eingerichtet.

## Validierung und verbleibende Grenzen

Lokal erfolgreich: 43 Backend-Tests (30 User, 13 Payment), 19 Frontend-Tests,
10 Deployment-Hilfswerkzeug-Tests mit simulierter AWS-CLI, Actionlint,
JSON-/Shell-Prüfung und die drei Docker-Builds. Der isolierte gemeinsame
PostgreSQL-15-Test bestätigt Login/JWT über beide Backends, localhost-Proxying,
401 für unberechtigte interne HTTP-Aufrufe, öffentliche Sperre interner Routen
und die Domainmigration. Kein echter Mail-/Stripe-/AWS-Aufruf fand statt.
Trivy 0.76.0 mit der am 16.10.2026 geladenen Datenbank meldete keine
HIGH-/CRITICAL-Schwachstellen, Secret-Funde oder fehlgeschlagenen
HIGH-/CRITICAL-Konfigurationsprüfungen im geprüften Source und den Images.

Nicht gegen AWS/Stripe/SES geprüft: tatsächliche Rollenrechte und Netzpfade,
RDS-TLS mit dem echten Endpunkt, Secret-Injektion, ALB/ACM, Stripe-Testkonto und
Preview-API-Zugriff für Connect-Onboarding. Der Code verwendet hierfür
`2026-09-30.endive`; Verfügbarkeit und Berechtigungen im Testkonto vor Nutzung
manuell bestätigen. Webhook-Verarbeitung ist lokal signiert getestet, ein
vollständiger Stripe-Test-Zahlungsfluss bleibt ein separat freizugebender Test.
Öffentliche Registrierungen/Passwort-Resets können nach einem späteren Start
reale SES-E-Mails auslösen.

Für SMTP-Diagnose und eine ausdrücklich freigegebene Secret-Korrektur
`deployment/scripts/smtp_secret.py` verwenden; siehe
[Registrierungs-/SMTP-Fix](registration-smtp-fix.md). Es verwendet verdeckte
Eingaben, prüft STARTTLS/AUTH ohne Mailversand und gibt keine Zugangsdaten aus.
Das ältere `aws/ses_smtp_password.py` gibt ein Passwort auf stdout aus und
soll dafür nicht verwendet werden. Es wurde nicht ausgeführt und bleibt durch
`.dockerignore` vom Image-Build ausgeschlossen.

Aktueller Integrationsstand und Anleitung für einen separat genehmigten
Stripe-Testbetrieb: [README: Stripe Connect](../README.md#stripe-connect-stand-und-manuelle-einrichtung).
Die dort beschriebenen Startprüfungen gelten für alle Umgebungen. Pooling darf
nicht aktiviert werden. Bestehende ECS-Vorlagen und Release-Builds bleiben auf
deaktivierten Zahlungen; zum genehmigten Aktivieren sind die dort genannten
Werte ausdrücklich anzupassen.

## Statisches Datenbankpasswort und Startfehler am 20.10.2026

Gewünschter Betrieb: statisches PostgreSQL-Passwort aus `gratilo/prd/database`
(JSON `username` und `password`), keine automatische Passwortrotation.
Die ECS-Secret-Referenzen bleiben auf diesem Secret. Passwörter gehören weder
in Git noch in Shell-Argumente, Logs oder JDBC-URLs.

Die lesende Untersuchung am 20.10.2026 ergab:

- RDS `gratilo-postgres-prd` war `available`, PostgreSQL 15.19, privat.
- RDS rotierte das separat verwaltete Master-Secret um 08:09 Uhr MESZ.
- `gratilo/prd/database` wurde zuletzt am 16.10. geändert. Ein Vergleich nur
  im Arbeitsspeicher bestätigte: Benutzer identisch, Passwörter verschieden.
- CloudWatch meldete `FATAL: password authentication failed for user "postgres"`.
  ECS hatte 0 laufende Tasks bei Sollwert 1, die ALB-Target-Group keine Targets.
  Revisionen 10, 11 und 12 verwenden dieselbe veraltete Passwortreferenz;
  deshalb behebt ein Image-Rollback diesen Authentifizierungsfehler nicht.

Vorbereitete Reparatur: RDS-Passwortverwaltung abschalten und als Master-Passwort
den vorhandenen Wert aus `gratilo/prd/database` setzen. AWS löscht dabei
automatisch das **RDS-verwaltete** Secret; das Anwendungssecret bleibt bestehen.
Siehe [AWS ModifyDBInstance](https://docs.aws.amazon.com/AmazonRDS/latest/APIReference/API_ModifyDBInstance.html).
Diese Nebenwirkung muss vor Ausführung ausdrücklich freigegeben sein.

Die sichere Vorschau liest nur Metadaten:

```bash
python3 deployment/scripts/static_rds_password.py
```

Erst nach Freigabe der Passwortänderung einschließlich der Secret-Löschung:

```bash
python3 deployment/scripts/static_rds_password.py --apply --acknowledge-managed-secret-deletion
```

Das Skript bindet Konto, Profil, Region, Datenbank und VPC fest, verweigert
öffentliche Datenbanken und ausstehende RDS-Änderungen. Das Passwort wird nur
im Speicher verarbeitet und per stdin an die CLI übergeben. Kein
`ApplyImmediately` für andere Änderungen, kein Reboot, keine Netzwerkänderung,
kein neues Deployment. Die Passwortverwaltung ändert AWS unmittelbar.
Nach Abschluss: RDS ohne `MasterUserSecret` prüfen, ECS-Neustartversuche beobachten,
Running=Desired=1, alle drei Container gesund, ALB-Target gesund und HTTPS prüfen.
Ein laufendes Pipeline-Skript kann sich bereits im Rollback befinden; zuerst
dessen Zustand klären, bevor eine freigegebene Release-Version erneut deployt wird.
Ein erfolgreicher Passwortwechsel allein bestätigt noch keine gesunde Anwendung.

## DBeaver: geprüfter privater Zugang am 20.10.2026

Konto `070351676632`, Profil `gratilo`, Region `eu-central-1`.
Die aktuelle Rechner-IPv4 war `217.253.49.160`, unabhängig über
`checkip.amazonaws.com` und `api.ipify.org` ermittelt. Vor jeder Freigabe erneut prüfen.

| Ressource | Bestand / Abhängigkeit |
| --- | --- |
| VPC | `gratilo-prd-vpc`, `vpc-07825b07bcd00d0d1`, `10.42.0.0/16` |
| RDS | `gratilo-postgres-prd`, `PubliclyAccessible=false`, verschlüsselte Speicherung |
| DB-Subnetzgruppe | `gratilo-prd-db-subnet-group`, beide privaten Subnetze |
| Privat 1a | `subnet-08a0a07b07ba145d7`, `10.42.128.0/20`, Route `rtb-0717e64d6b949154f` |
| Privat 1b | `subnet-0c25e23bc429c8e67`, `10.42.144.0/20`, Route `rtb-07c731cca16f1fbf1` |
| Private Default-Routen | bestehendes regionales NAT `nat-17a6b349ebc05b283` |
| Öffentlich 1a | `subnet-06b9a46b90f2af341`, `10.42.0.0/20` |
| Öffentlich 1b | `subnet-0dfbfaadeb94a50e7`, `10.42.16.0/20` |
| Öffentliche Routen | `rtb-06cfebe01b3df2502` → `igw-050b21e12eda88878` |
| NAT-Routentabelle | `rtb-00661d0ea27dfebf5` → dasselbe Internet-Gateway |
| Main-Routentabelle | `rtb-0de237a23aac7ab8a`, nur VPC-lokal |
| RDS-SG | `sg-0b31d54acc7c91867`, TCP 5432 ausschließlich von ECS- und Zugangs-SG |
| ECS-SG | `sg-0cdb5b617a4dfd97d`, Frontend-Port 80 ausschließlich vom ALB |
| ALB-SG | `sg-0eb4dd906ce35350b`, öffentliche Ports 80/443 für den produktiven Webdienst |
| NACL | `acl-0f385b9157243f32a`, Standard-ACL aller vier Subnetze |

ECS `gratilo-prd-app` nutzt die beiden privaten Subnetze ohne öffentliche IP.
ALB `gratilo-prd-alb` nutzt die beiden öffentlichen Subnetze. VPC, alle vier
Subnetze, Routing, Internet-Gateway, NAT samt zugehörigen IPs, ALB/Target-Group,
RDS und deren produktive SGs müssen erhalten bleiben. NAT wird auch für
ECR/S3-Images, CloudWatch, Secrets Manager und den SSM-Agent benötigt.
Keine VPC-Endpunkte, Client-VPN-Endpunkte oder Site-to-Site-VPN-Verbindungen
in der geprüften Region gefunden. DNS-Support und DNS-Hostnames sind aktiviert.

**Eine `/32`-Security-Group-Regel allein ermöglicht keinen Internetzugriff auf
diese private RDS-Instanz.** Das NAT ermöglicht keine eingehenden Verbindungen.
Direktzugriff erforderte `PubliclyAccessible=true` und geeignete öffentliche
DB-Subnetze mit Internet-Gateway-Routen. Das kann eine Subnetzgruppenänderung,
Unterbrechungen und öffentliche Exposition mit sich bringen. Die aktuellen
privaten Subnetzrouten werden auch von ECS genutzt und dürfen nicht für
DBeaver umgestellt werden. Empfehlung: vorhandenen SSM-Zugang beibehalten.
Siehe [AWS-Netzwerkszenarien](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_VPC.Scenarios.html).

Vorhandener Zugangsrechner `gratilo-prd-db-access`: `i-09fe8ff6c84bda64b`,
`t3.nano`, Amazon Linux 2023, private IP `10.42.135.43`, keine öffentliche IP,
im privaten Subnetz 1a. SSM-Agent 3.3.5390.0 war online. SG
`sg-0802b2876336a911b` hat keine Ingress-Regeln. Instanzprofil und Rolle
`gratilo-prd-db-access-ssm-role` verwenden nur `AmazonSSMManagedInstanceCore`,
keine Inline-Policies. Lokal sind AWS CLI und Session Manager Plugin installiert.

Nach gesonderter Freigabe kann der vorhandene Tunnel gestartet werden
(Bash; bei Windows-DBeaver CLI/Plugin vorzugsweise ebenfalls unter Windows starten):

```bash
aws ssm start-session --profile gratilo --region eu-central-1 \
  --target i-09fe8ff6c84bda64b \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters '{"host":["gratilo-postgres-prd.chocqs4ummy7.eu-central-1.rds.amazonaws.com"],"portNumber":["5432"],"localPortNumber":["15432"]}'
```

| DBeaver-Einstellung | Wert |
| --- | --- |
| Host | `gratilo-postgres-prd.chocqs4ummy7.eu-central-1.rds.amazonaws.com` |
| Port mit SSM | `15432` (Weiterleitung an RDS 5432) |
| Datenbank | `gratilodb` (aus der aktiven ECS-Konfiguration bestätigt) |
| Benutzer / Passwort | autorisierte Datenbank-Zugangsdaten, aktuell aus `gratilo/prd/database` nach Reparatur |
| SSL-Modus | `verify-full`, Hostnamenprüfung und Zertifikatsvalidierung aktiv |
| Root-CA | offizielles [Frankfurt-RDS-CA-Bundle](https://truststore.pki.rds.amazonaws.com/eu-central-1/eu-central-1-bundle.pem) |

Für `verify-full` muss der echte RDS-Hostname als JDBC-Host erhalten bleiben.
Dazu temporär ausschließlich auf dem DBeaver-Rechner in der Hosts-Datei
`127.0.0.1 gratilo-postgres-prd.chocqs4ummy7.eu-central-1.rds.amazonaws.com`
abbilden. Der SSM-Agent löst den Host unabhängig innerhalb der VPC auf.
Nach Nutzung den Eintrag wieder entfernen. `localhost` als JDBC-Hostname
würde nicht zum RDS-Zertifikat passen. Keine unvalidierende SSLFactory verwenden.
RDS erzwingt bereits `rds.force_ssl=1`, `ssl=1`; CA `rds-ca-rsa2048-g1`.
Der Tunnel und eine SQL-/TLS-Verbindung wurden bei dieser Analyse nicht gestartet.

Die `/32`-Beschränkung muss bei SSM am Sitzungszugang umgesetzt werden:
geplant sind eine eigene DBeaver-Benutzerrolle mit `aws:SourceIp=217.253.49.160/32`
für Start/Resume und ein eigenes Session-Dokument mit festem RDS-Ziel/Port 5432,
Zielinstanz ausschließlich `i-09fe8ff6c84bda64b`, Zugriff nur auf eigene Sessions.
Andere Shell-/Portforwarding-Dokumente und breitere parallele Berechtigungen
dürfen die Einschränkung nicht umgehen. Das aktuell genutzte SSO-Administratorprofil
ist bereits weitreichend berechtigt; eine neue Allow-Policy allein schränkt es
nicht ein. Die IP-Bedingung kontrolliert API-Aufrufe, keine fortlaufende Prüfung
bereits offener Tunnel. Bei IP-Wechsel bestehende Sessions beenden.
RDS sieht beim Tunnel die Zugangsinstanz, nicht die öffentliche Rechner-IP.

Es wird keine öffentliche RDS-SG-Regel empfohlen. Der ausschließlich hypothetische
Direktzugriff wäre TCP 5432, Quelle `217.253.49.160/32`, Beschreibung
`Temporary DBeaver access`, Gruppe `sg-0b31d54acc7c91867`; niemals größere Ranges.
Bei später ausdrücklich genehmigter Anlage die neue `SecurityGroupRuleId`
festhalten und anschließend exakt diese Regel widerrufen. Beim empfohlenen
SSM-Weg gibt es keine öffentliche SG-Regel zu entfernen: Tunnel beenden,
temporäre IAM-Berechtigung entfernen und lokalen Hosts-Eintrag löschen.

### Hilfsressourcen und möglicher späterer Rückbau

Die Zugangsinstanz und ihre SG sind eindeutig für privaten DB-Zugang vorgesehen;
eine ausschließlich DBeaver betreffende Nutzung ist allein aus Metadaten nicht
beweisbar. Es wurden keine weiteren EC2-Instanzen, Launch-Templates, Auto-Scaling-
Gruppen, aktiven SSM-Sessions oder eigenen EBS-Snapshots gefunden.
Die Rolle hat nur das genannte Instanzprofil; die SG hängt nur an dieser
Instanz. Die einzige gefundene eingehende SG-Referenz ist RDS-Regel
`sgr-067526ca574fca6b2`, TCP 5432 von der Zugangs-SG.

Nur bei bestätigter Nichtnutzung und Ersatz des Zugangs wären entfernbar:

1. Instanz `i-09fe8ff6c84bda64b`; ihr Root-Volume `vol-0b6167009a3e2ce3f`
   und ENI `eni-0e060691e8be457d4` haben `DeleteOnTermination=true`.
   Vorher lokale Inhalte und laufende Benutzerprozesse prüfen; das wurde nicht geprüft.
2. Exakt RDS-Regel `sgr-067526ca574fca6b2`, dann SG `sg-0802b2876336a911b`.
3. Instanzprofil/Rolle `gratilo-prd-db-access-ssm-role` nach erneuter
   Abhängigkeitsprüfung und Entfernung der Policy-Anbindung.

Diese Ressourcen sind für den empfohlenen SSM-Weg weiterhin erforderlich.
Die produktive ECS→RDS-Regel `sgr-0b1799ed8b66a265a` bleibt erhalten.
Keine VPC-, NAT-, ALB-, ECS-, RDS- oder produktiven SG-Löschungen geplant.
