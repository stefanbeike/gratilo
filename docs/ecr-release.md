# Manueller ECR-Release ohne Stripe

Der Workflow `Gratilo ECR Release (payments disabled)` (`release.yml`) baut alle
drei Images, führt CI/Tests und blockierende Trivy-Scans für HIGH/CRITICAL aus.
Er veröffentlicht nur nach manueller Environment-Freigabe. Es gibt im aufgerufenen
`007_deploy_ecs.yml` keinen ECS-Deploy-Job mehr, keine Task-Registrierung und kein
Service-Update. Terraform/Crossplane werden nicht ausgeführt.

## Exakte GitHub-Konfiguration

Repository: `stefanbeike/gratilo` → Settings → Secrets and variables → Actions →
Variables:

| Repository Variable | Wert |
|---|---|
| `ECR_PUBLISH_ENABLED` | `true` |

`STRIPE_PUBLISHABLE_KEY` ist nicht erforderlich und wird vom Release nicht gelesen.
Keine Stripe- oder AWS-Access-Key-Secrets in GitHub anlegen. Optional bestehende
Sonar-Variablen/Secrets beibehalten; bei aktiviertem Sonar muss dessen Gate bestehen.

Settings → Environments → `production`:

| Environment Variable | Wert |
|---|---|
| `AWS_ACCOUNT_ID` | `070351676632` |
| `AWS_REGION` | `eu-central-1` |
| `AWS_DEPLOY_ROLE_ARN` | `arn:aws:iam::070351676632:role/gratilo-github-actions-role` |

Deployment branches: ausschließlich `main`. Required reviewers aktivieren;
Self-review/Bypass nach gewünschtem Freigabemodell einschränken. Bei gesperrter
Selbstfreigabe muss eine andere berechtigte Person den Lauf genehmigen können.
Der Tarif muss diese Environment-Freigaben für das Repository unterstützen.
Ein Environment ohne Required reviewers gewährleistet keine manuelle Freigabe.

Der OIDC-Trust muss `aud=sts.amazonaws.com` und genau
`sub=repo:stefanbeike@13099702/gratilo@1351208624:environment:production` zulassen. Ein ausschließlich
auf `repo:stefanbeike@13099702/gratilo@1351208624:ref:refs/heads/main` begrenzter Subject passt wegen des
Environments nicht. Vorlage: `deployment/iam/github-oidc-production-trust.json`.
Keine IAM-Änderung wird durch diesen Workflow vorgenommen.

Die GitHub-Rolle benötigt nur ECR-Login/Push/Describe auf
`gratilo/frontend`, `gratilo/user-service`, `gratilo/payment-service`.
Policy-Vorlage: `deployment/iam/github-deployment-policy.json`.
Keine ECS-, iam:PassRole- oder Anwendungs-Secret-Leserechte erforderlich.
Repos manuell auf unveränderliche Tags konfigurieren; der Workflow verwendet
`sha-<Commit-SHA>-<Run-ID>-<Run-Attempt>` konsistent für alle drei Images und
veröffentlicht kein `latest`. Die Job Summary zeigt Tags und Image-Digests.

## Ersten Release starten

1. Die Änderungen dieses Branches nach Review auf GitHub-main übernehmen.
   Diese Vorbereitung führt selbst keinen Push oder Workflow-Lauf aus.
2. Die obigen GitHub-Einstellungen, OIDC-Trust und ECR-Push-Rechte prüfen.
3. GitHub → Actions → **Gratilo ECR Release (payments disabled)** → Run workflow.
4. Branch **main** wählen und als Bestätigung exakt
   **`PUBLISH gratilo-sandbox`** eingeben (der bestehende Bestätigungstext bleibt
   unverändert). Run workflow anklicken.
5. CI, Backend-/Frontend-Tests, Workflow-Prüfung, drei Docker-Builds, drei Image-
   Scans und den isolierten Container-Test abwarten. Bei Fehlern wird nichts
   veröffentlicht; Findings korrigieren und einen neuen vollständigen Lauf starten.
6. Beim wartenden Job `publish` **Review deployments** öffnen, `production`
   auswählen und nach Prüfung genehmigen. Erst danach erfolgt die OIDC-Anmeldung
   und der Push der bereits geprüften Image-Artefakte nach ECR.
7. In der Job Summary alle drei ECR-Referenzen und den gemeinsamen Tag prüfen.
   Es startet kein ECS-Deployment. Image-Artefakte laufen nach einem Tag ab;
   bei längeren Freigabezeiten einen neuen vollständigen Lauf starten.

## Anwendung ohne Zahlungen

Backend: `PAYMENTS_ENABLED=false` ist der sichere Default. Es werden weder
Stripe-Schlüssel noch Webhook-Secrets benötigt. Das Produktionsprofil startet
weiterhin mit DB-/JWT-/internen Secrets, aber ohne Stripe-Konfiguration.
Frontend: Der Release setzt beim Build `VITE_PAYMENTS_ENABLED=false` und gibt
keinen Stripe-Key mit. Eine zur Laufzeit gesetzte ECS-Variable ändert den bereits
gebauten Frontend-Code nicht.

Der Backend-Filter sperrt Checkout, Bestätigung, Onboarding/Sync, Webhooks,
Auszahlungen und Transaktionsänderungen vor Ausführung der Controller mit
HTTP 503 / `PAYMENTS_DISABLED`. Das betrifft echte, Test- und Demo-Zahlungen.
Gespeicherte Transaktionen und gespeicherter Accountstatus bleiben lesbar;
Healthchecks funktionieren. Die Oberfläche zeigt deaktivierte Zahlungen,
startet kein Stripe-SDK und ermöglicht weder Zahlungsaktivierung noch Auszahlung.
Die ECS-JSON-Vorlagen enthalten `PAYMENTS_ENABLED=false`, deaktivierte
Auszahlungssimulation und keine Stripe-Secret-Referenzen.

Der gemeinsame Smoke-Test startet ohne Stripe-Konfiguration in einem internen
Docker-Netz ohne Internet. Er prüft die Sperren über echte HTTP-Anfragen,
Login/JWT, Healthchecks und `count(*) = 0` in der Transaktionstabelle. Es werden
keine echten oder simulierten Zahlungen ausgeführt. Lokale Tests können OIDC,
ECR-Berechtigungen und GitHub-Environment-Einstellungen nicht gegen AWS bestätigen.

Lokal validiert: 43 Backend-Tests, 19 Frontend-Tests, zehn Deployment-Tests,
Actionlint, Compose/JSON, alle drei Docker-Builds und der gemeinsame Container-Test
ohne Stripe-Konfiguration mit null Transaktionen. Trivy 0.76.0 mit der bereits
am 16.10.2026 geladenen Datenbank: keine HIGH-/CRITICAL-, Secret- oder
HIGH-/CRITICAL-Konfigurationsfunde im geprüften Source und den Images. Die
GitHub-Pipeline lädt für jeden Lauf ihre Scanner-Datenbanken regulär neu.

Bei `Not authorized to perform sts:AssumeRoleWithWebIdentity` siehe
[OIDC-Diagnose und bestätigte Subject-Korrektur](oidc-troubleshooting.md).
