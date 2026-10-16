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
Alle drei Repos müssen manuell auf **IMMUTABLE ohne Ausnahmen** stehen.
`MUTABLE` und `IMMUTABLE_WITH_EXCLUSION` werden vor dem ersten Push abgelehnt.
Die GitHub-Rolle benötigt zusätzlich `ecr:DescribeRepositories` auf genau diese
Repos, um die Einstellung zu lesen. Der Workflow ändert sie nicht.
Für jeden Service werden `vX.Y.Z` und der bisherige
`sha-<Commit-SHA>-<Run-ID>-<Run-Attempt>`-Tag veröffentlicht, jeweils mit identischem
Digest. Es gibt kein `latest`. Die Job Summary zeigt Release-Art, alte/neue Version,
Commit-SHA, Git-Tag, alle ECR-Referenzen/Digests und das Veröffentlichungsergebnis.

## Ersten Release starten

1. Die Änderungen dieses Branches nach Review auf GitHub-main übernehmen.
   Diese Vorbereitung führt selbst keinen Push oder Workflow-Lauf aus.
2. Die obigen GitHub-Einstellungen, OIDC-Trust und ECR-Push-Rechte prüfen.
3. GitHub → Actions → **Gratilo ECR Release (payments disabled)** → Run workflow.
4. Branch **main** wählen. Im Pflichtfeld **version_bump** `patch` (Default),
   `minor` oder `major` wählen. Als Bestätigung exakt **`PUBLISH gratilo-sandbox`**
   eingeben und Run workflow anklicken.
5. Die geplante Version in der Summary prüfen. CI, Backend-/Frontend-Tests, Workflow-Prüfung, drei Docker-Builds, drei Image-
   Scans und den isolierten Container-Test abwarten. Bei Fehlern wird nichts
   veröffentlicht; Findings korrigieren und einen neuen vollständigen Lauf starten.
6. Beim wartenden Job `publish` **Review deployments** öffnen, `production`
   auswählen und nach Prüfung genehmigen. Erst danach erfolgt die OIDC-Anmeldung
   und der Push der bereits geprüften Image-Artefakte nach ECR.
7. In der Job Summary alle drei ECR-Referenzen, beide Image-Tags und den nach erfolgreichem Push erstellten Git-Tag prüfen.
   Es startet kein ECS-Deployment. Image-Artefakte laufen nach einem Tag ab;
   bei längeren Freigabezeiten einen neuen vollständigen Lauf starten.

## SemVer: Git-Tags als einzige Versionshistorie

`006_release_tag.yml` plant jetzt nur noch lesend die Version. Es legt vor den
Builds keinen Git-Tag mehr an. Die GitHub-API liest alle Seiten der Git-Tag-Liste;
berücksichtigt werden ausschließlich kanonische stabile Tags `vMAJOR.MINOR.PATCH`
ohne führende Nullen. Vergleich als numerisches Integer-Tupel, daher ist
`v1.10.0` höher als `v1.9.99`. Prereleases, Build-Metadaten und andere Tags werden
ignoriert. Ohne stabilen Tag ist die Basis `v0.0.0`.

| Auswahl | Beispiel | Erster Release ohne stabile Tags |
|---|---|---|
| `patch` | `v1.2.3` → `v1.2.4` | `v0.0.1` |
| `minor` | `v1.2.3` → `v1.3.0` | `v0.1.0` |
| `major` | `v1.2.3` → `v2.0.0` | `v1.0.0` |

Die Release-Version stammt ausschließlich aus diesen Git-Tags; keine zusätzliche
Versionsdatei wird gepflegt. Gradle-/npm-Versionen müssen für diesen ECR-Release
nicht manuell angepasst werden. Es wird ein leichtgewichtiger Git-Tag auf den
getesteten Commit erstellt, kein zusätzlicher GitHub-Release-Eintrag.

## Konflikte, Wiederholungen und Teilveröffentlichungen

Die bestehende Concurrency-Gruppe `gratilo-production-release` serialisiert den
gesamten Lauf einschließlich Planung, Tests und Environment-Freigabe; laufende
Releases werden nicht abgebrochen. GitHub kann ältere wartende Pending-Läufe durch
neuere ersetzen. Andere manuelle Tag-Schreiber sind nicht durch diese Gruppe
gesperrt; deshalb bleiben die zusätzlichen Remote-Prüfungen notwendig.

Nach Freigabe prüft der Publish-Job die geplante Git-Version erneut. Alle drei
ECR-Repositories müssen strikt immutable sein; keiner der sechs Ziel-Tags darf
existieren. Erst nach vollständiger Vorprüfung und Laden der geprüften Artefakte
wird die Git-Historie unmittelbar vor dem ersten Push nochmals geprüft. Wenn die
höchste stabile Git-Version inzwischen fortgeschritten ist, endet der Lauf ohne
Push. Jeder Service erhält beide Tags aus demselben geladenen Image. Beide
ECR-Digests müssen nach dem Push übereinstimmen.

Nach allen sechs bestätigten Image-Tags wird die Git-Historie erneut geprüft und
`refs/tags/vX.Y.Z` über die Create-only-API angelegt. Es gibt kein Force-Push,
Ref-Update oder automatisches Löschen. Auch eine konkurrierende Tag-Erstellung
wird damit abgewiesen. ECR-Tag-Immutability schützt gegen konkurrierende externe
Pushes; ein Git-Konflikt kann niemals vorhandene Git-Tags verschieben.

Ein erneuter **Publish-Job mit derselben Planung** bricht bei bestehenden Git-
oder ECR-Tags ab. Vor irgendeinem Push fehlgeschlagene Läufe können wiederholt
werden, solange die geplante Version frei und die Git-Historie unverändert ist.
**Re-run all jobs** führt die Versionsplanung erneut aus und kann nach einem
bereits erfolgreichen Release die nächste Version berechnen (mit neuem
Run-Attempt-SHA-Tag). Diese neue Version ist vor erneuter Freigabe zu prüfen.
Für einen bewusst neuen Release einen neuen manuellen Workflow-Lauf starten.

Zwischen drei ECR-Repositories und GitHub gibt es keine gemeinsame Transaktion.
Wenn einzelne Pushes fehlschlagen oder die Git-Tag-Erstellung nach dem Push nicht
bestätigt werden kann, wird der Lauf rot und die Summary meldet ausdrücklich
**TEILVERÖFFENTLICHUNG MÖGLICH**, bekannte erfolgreiche Pushes und Digests.
Der Git-Tag wird bei fehlenden/abweichenden Image-Digests nicht erstellt.
Es wird nicht automatisch versucht, dieselbe Version zu überschreiben oder zu
überspringen. Ohne Git-Tag würde die nächste Planung erneut dieselbe Version
wählen und an bereits vorhandenen ECR-Tags scheitern. Ein Operator muss in diesem
Fall die Teilveröffentlichung anhand Commit, Artefakten, beiden Tags und Digests
prüfen und unter separater Freigabe vervollständigen oder bereinigen. Einen
fehlenden Git-Tag erst nach Bestätigung aller drei Images anlegen. Keine
ungeprüften Tags als vermeintliches Recovery setzen.

## Zusätzliche Berechtigungen und Regeln vor dem ersten SemVer-Release

- Nur der aufrufende und aufgerufene **Publish-Job** erhalten `contents: write`,
  zusätzlich zu ihrem bisherigen `id-token: write`. Versionsplanung, CI und
  Builds bleiben ohne Git-Schreibrecht. `GITHUB_TOKEN` wird automatisch gestellt;
  kein PAT, AWS Access Key oder neues GitHub Secret nötig.
- Repository-/Organisationsregeln müssen dem Publish-Job das angeforderte
  `contents: write` erlauben. Tag-Rulesets für `v*` so prüfen, dass der Workflow-
  Token neue Tags erstellen darf und Updates/Löschen geschützt bleiben. Falls ein
  Ruleset die Tag-Erstellung verweigert, wird nichts umgangen: Nach ECR-Push endet
  der Lauf mit Fehler und muss manuell aufgearbeitet werden. Keine automatische
  Erweiterung von Bypass-Rechten oder Ausweich-Authentifizierung.
- Die OIDC-Rolle benötigt zusätzlich `ecr:DescribeRepositories` auf genau
  `gratilo/frontend`, `gratilo/user-service`, `gratilo/payment-service`.
  Angepasste Vorlage: `deployment/iam/github-deployment-policy.json`.
- Alle drei ECR-Repositories **IMMUTABLE ohne Ausschlüsse** konfigurieren und
  bestehende Git-/ECR-Versionstags auf Konsistenz prüfen. Keine Tags durch diesen
  Auftrag erzeugen. Das Schema bestehender Tags bestimmt den ersten Versionssprung.
- Die bisherigen Variables, Environment-Reviewer, main-Beschränkung und OIDC-
  Vertrauensbedingungen bleiben bestehen; es gibt keine neue Versionsvariable.

Quellen: [ECR-Tag-Immutability](https://docs.aws.amazon.com/AmazonECR/latest/userguide/image-tag-mutability.html),
[GitHub Create reference](https://docs.github.com/en/rest/git/refs#create-a-reference).

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

SemVer lokal validiert: 25 neue Tests für Versionsberechnung, Erstveröffentlichung, Konflikte,
Wiederholungen, Teil-Pushes und Tag-Protection-Fehler werden mit simulierten
GitHub-/ECR-Clients getestet. Zusammen mit den zehn bestehenden Deployment-Tests
sind 35 Python-Tests grün; zusätzlich fünf OIDC-Diagnose-Tests, Actionlint und
Python-/JSON-Syntaxprüfung. Kein Test führt einen echten Push oder API-Schreibzugriff aus.
