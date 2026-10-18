# Gratilo Release & Deploy Production

## Ein manueller Einstieg

GitHub → **Actions → Gratilo Release & Deploy Production → Run workflow**.
Branch **main** wählen, `version_bump` auf `patch`, `minor` oder `major` setzen und
als `confirmation` exakt **RELEASE DEPLOY gratilo-production** eingeben.

Der Workflow `release.yml` führt innerhalb desselben Laufs die gesamte Kette aus:

1. **Validate & Version**: Remote-main muss dem gestarteten Commit entsprechen.
   Die numerisch höchste stabile Remote-Git-Version wird ermittelt, ohne
   Prereleases/Build-Metadaten und ohne führende Nullen. Startwert v0.0.0.
   Patch/minor/major berechnet die nächste freie Git-Version; noch kein Tag.
2. **CI & Security**: bestehende Backend-/Frontend-Tests, PostgreSQL-
   Registrierungsregression, Actionlint, Deployment-/OIDC-Tests, Source-Security-
   Scan und konfigurierte Sonar-Quality-Gates aus `ci.yml`. Ein Fehler stoppt alles.
3. **Build & Scan**: alle drei Linux/amd64-Images genau einmal bauen; Trivy
   HIGH/CRITICAL einschließlich ungefixter Findings blockiert. Gemeinsamer
   Drei-Container-/PostgreSQL-15-Test. Nur diese geprüften Images werden als
   Artefakte übergeben, nicht im Publisher oder Deploy erneut gebaut.
4. **Publish ECR**: im Environment **release**, OIDC nach erfolgreicher CI.
   main-Commit, Remote-Tags und ECR-Konflikte erneut prüfen. Alle drei Images
   mit derselben SemVer-Version und dem gemeinsamen
   `sha-<commit>-<run>-<attempt>` veröffentlichen, beide Tags je Image auf gleichen
   Digest prüfen. Erst nach sechs bestätigten ECR-Referenzen einen **annotierten
   Git-Tag** über die GitHub Git-API anlegen und seine Commit-Zuordnung bestätigen.
5. **Deploy Production**: ausschließlich nach erfolgreichem Publish und Tag.
   Wartet am Environment **production** auf eine manuelle Freigabe; der Starter
   darf selbst freigeben. Erst
   danach liest der Job die Review-Historie und fordert AWS-Credentials an.
   ECR-Digests und Build-Provenienz müssen exakt den Outputs dieses Publish-Jobs
   entsprechen. Vorhandene Task klonen, nur die drei Images ändern, neue Revision
   registrieren und bestehenden Service kontrolliert aktualisieren.

Der Produktionsjob bleibt ohne nachgewiesene Production-Freigabe gesperrt.
Ein unabhängiger Zweitprüfer ist nicht erforderlich; Selbstfreigabe ist erlaubt.
Die Eingabe beim Start ist keine Deployment-Freigabe. In Actions beim wartenden
Job **Review deployments → production → Approve** verwenden; danach läuft die
Kette ohne weiteren manuellen Workflow fort.

**Nur release.yml hat workflow_dispatch.** Kein Tag-Push und kein normaler
main-Push startet einen Release oder Deploy. Normale main-/PR-Ereignisse behalten
ihre CI-Prüfungen. `006_release_tag.yml`, `007_deploy_ecs.yml` und
`deploy-production.yml` sind ausschließlich wiederverwendbare Teilworkflows.
Der frühere Tag-Release und der separate manuelle Deployment-Einstieg entfallen.

## Einmalige GitHub-Konfiguration

Repository `stefanbeike/gratilo`, Repository Variable:

| Variable | Wert |
|---|---|
| `ECR_PUBLISH_ENABLED` | `true`, sobald die folgende Einrichtung geprüft ist |

`release.yml` liest diesen Schalter außerhalb eines Environment-Jobs und übergibt
ihn als erforderlichen String-Input `ecr_publish_enabled` an den Publisher.
Nur exakt `true` erlaubt Veröffentlichung; fehlende oder andere Werte brechen ab.
Keine gleichnamigen Environment-Variablen als Ersatz anlegen. Der Schalter ist
kein Secret und keine Produktionsfreigabe.

Zwei Environments anlegen bzw. konfigurieren: **release** und **production**.
Beide auf **Branch main** beschränken; Tag-Regeln werden nicht mehr benötigt.
In **beiden Environments** die folgenden Variables hinterlegen:

| Variable | Wert |
|---|---|
| `AWS_ACCOUNT_ID` | `070351676632` |
| `AWS_REGION` | `eu-central-1` |
| `AWS_DEPLOY_ROLE_ARN` | `arn:aws:iam::070351676632:role/gratilo-github-actions-role` |

Die vorhandenen Variablen nur im Environment production sind für den Publisher
im neuen Environment release nicht sichtbar; dort ebenfalls hinterlegen.
Alternativ können diese nicht geheimen Werte Repository Variables sein.
Bei Environment-Variablen müssen die drei Werte in **release und production**
vorhanden sein. `production` vererbt seine Konfiguration nicht an `release`.
Die Validierung prüft jeden Wert vor OIDC, meldet fehlende Variablen einzeln
mit Environment und Einstellungsort und gibt keine Konfigurationswerte aus.
Auch `AWS_REGION` muss ausdrücklich gesetzt sein; es gibt keinen stillen Default.

- **release** benötigt keine Required reviewers: Tests/Scans und expliziter Start
  schützen die Veröffentlichung; die manuelle Produktionsfreigabe folgt danach.
- **production**: Required reviewers, **Prevent self-review ausgeschaltet**, kein
  Administrator-Bypass. `stefanbeike` kann seinen eigenen Lauf freigeben;
  eine zweite Person ist nicht notwendig. main und Änderungen an Workflows schützen.
- Der Deploy prüft die GitHub-Review-Historie desselben Runs zusätzlich:
  menschlicher Reviewer, Environment production, Freigabe sowie passender
  Workflow/Commit/Run/Attempt. Starter und Retry-Starter sind als Reviewer erlaubt.
  Ablehnung, fehlende
  Freigabe oder nicht lesbare API stoppen den Job **vor AWS/OIDC**.
- Fehlen Required reviewers im verwendeten GitHub-Tarif, gibt es **keinen stillen
  automatischen Ersatz**. Der Deploy scheitert ohne Review-Nachweis. Zuerst einen
  unterstützten Tarif/Repository-Modus bzw. eine separat geprüfte Freigabelösung
  bereitstellen. Die Startbestätigung oder eine Variable genügt nicht.
- Rulesets für `v*` müssen GitHub Actions das Erstellen annotierter Tags erlauben.
  Verschieben/Löschen vorhandener Tags gesperrt lassen; keine Force-Pushes oder
  automatischen Ruleset-Bypässe. Tag-Protection-Fehler werden als Fehler gemeldet.

Keine zusätzlichen GitHub Secrets, AWS Access Keys oder Stripe-Keys erforderlich.
Optionale bestehende Sonar-Konfiguration bleibt erhalten (`SONAR_PROJECT_KEY`,
`SONAR_ORGANIZATION`, `SONAR_TOKEN`, `SONAR_HOST_URL`). Nur Publish erhält GitHub
`contents: write`; Deploy zusätzlich `actions: read` für die Review-Historie.
Beide AWS-Jobs erhalten `id-token: write`, sonst bleiben Tokens read-only.

[GitHub Environments](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments),
[Review-Historie und Actions-read-Berechtigung](https://docs.github.com/en/rest/actions/workflow-runs#get-the-review-history-for-a-workflow-run).

## Nachgewiesener Konfigurationsfehler am 18.10.2026

**Aktueller Stand nach der anschließenden Einrichtung:** Die drei AWS-Variablen
sind inzwischen auch im Environment release vorhanden. Nach ausdrücklicher
Freigabe wurde die Trust-Policy um den exakten release-Subject ergänzt und
anschließend aus AWS zurückgelesen. Die untenstehenden ursprünglichen Hinweise
zu diesen fehlenden Werten dokumentieren den damaligen Fehlerzustand.

Der fehlgeschlagene [Lauf 39118328665](https://github.com/stefanbeike/gratilo/actions/runs/39118328665)
verwendet Commit `0f85e0039ac7495aa4aeb5e542ebd0cd31dfbce1`. Die damalige
Workflow-Datei wurde direkt für diesen Commit geprüft. CI, alle Docker-Builds,
Trivy und der gemeinsame Container-Test waren erfolgreich; der Publish-Job
scheiterte vor OIDC und ECR, Deploy wurde übersprungen.

**Der Schalter war nicht der Fehler:** Das Runner-Log enthält `ENABLED: true`.
Die Repository-Variable ist seit 16.10.2026 unverändert `true`. Der Text
`ECR_PUBLISH_ENABLED must be true` stammt in diesem Lauf nur aus der
automatischen Anzeige des Shell-Skripts, nicht aus einem ausgeführten Fehlerzweig.
Die nächste, bisher kommentarlos abbrechende Prüfung scheitert am leeren
`AWS_ACCOUNT_ID`; auch `AWS_DEPLOY_ROLE_ARN` ist leer.

Rein lesende GitHub-CLI-Abfragen bestätigen: Environment **release** hat
**0 Variablen**, Environment **production** hat die drei AWS-Variablen aus
obiger Tabelle. Es gibt keine Environment-Overrides des Repository-Schalters.
Die regionale Fallback-Konfiguration hatte nur den fehlenden Regionswert verdeckt.
Wiederverwendbare Workflows verwenden die Repository-Variablen des Aufrufers;
sie erhalten dadurch keinen Zugriff auf ein anderes Environment.
[GitHub-Variablenreferenz](https://docs.github.com/en/actions/reference/workflows-and-actions/variables).

### Noch manuell erforderlich, ohne Änderung durch diesen Fix

1. **Settings → Environments → release → Environment variables:**
   `AWS_ACCOUNT_ID=070351676632`, `AWS_REGION=eu-central-1`,
   `AWS_DEPLOY_ROLE_ARN=arn:aws:iam::070351676632:role/gratilo-github-actions-role`
   ergänzen. Die vorhandenen drei Werte in production beibehalten.
   Alternativ dieselben drei Werte als Repository Variables für beide Jobs
   hinterlegen und abweichende Environment-Werte bewusst prüfen.
   Die vorhandene Repository-Variable `ECR_PUBLISH_ENABLED=true` bleibt bestehen.
2. **release → Deployment branches and tags:** Selected branches, ausschließlich
   Branch `main`. Aktuell fehlen Environment-Branchregeln; der Workflow selbst
   ist bereits auf manuelle main-Läufe eingeschränkt. Keine Reviewerpflicht
   des Produktionsjobs nach release verlagern.
3. **production → Required reviewers:** `stefanbeike` kann eingetragen bleiben;
   **Prevent self-review ausgeschaltet lassen**. Diese aktuelle Konfiguration
   erlaubt nach der Codekorrektur auch die Freigabe des eigenen Laufs.
   Die bestehende Beschränkung auf Branch `main` und **kein Admin-Bypass**
   sind bereits korrekt und bleiben erhalten.
4. **AWS IAM**, erst nach ausdrücklicher Freigabe: Die lesend geprüfte aktuelle
   Trust-Policy erlaubt ausschließlich den production-Subject. Für den Publisher
   zusätzlich den exakten release-Subject aus
   `deployment/iam/github-oidc-trust-policy.json` erlauben; Audience unverändert
   `sts.amazonaws.com`. Ohne diese manuelle Ergänzung scheitert der anschließende
   OIDC-Schritt auch nach erfolgreicher Variablenkorrektur. Es werden dafür keine
   neuen GitHub-Tokenberechtigungen oder statischen AWS-Schlüssel benötigt.

Die Korrektur muss zuerst kontrolliert nach main übernommen werden. Danach einen
**neuen** manuellen Lauf von main starten; **Re-run jobs** des alten Laufs
verwendet weiterhin dessen alten Workflow-Commit. In diesem Arbeitsauftrag
wurde kein Workflow gestartet und keine GitHub- oder AWS-Einstellung verändert.

Lokale Validierung: 79 Python-Tests (einschließlich 11 neuer Konfigurations- und
Workflow-Regressionsfälle), 7 OIDC-Tests sowie Actionlint erfolgreich. Die Tests
decken den tatsächlichen Fehlerkontext, strikte Schalterprüfung, fehlende/
ungültige AWS-Werte, sichere Diagnosen und die unveränderte Kette
Version → CI → Build/Trivy → Publish → Production-Freigabe → Deploy ab.
Ein echter vollständiger Release-/Deploy-Lauf wurde ausdrücklich nicht ausgeführt.

## Production-Freigabe ohne Zweitprüfer

Auf ausdrücklichen Wunsch ist kein unabhängiger zweiter Reviewer mehr nötig.
Der fehlgeschlagene Lauf `39121335772` enthielt bereits eine menschliche
production-Freigabe von `stefanbeike`; die zusätzliche Identitätsprüfung hatte
diese Selbstfreigabe abgewiesen. `production_approval.py` akzeptiert jetzt auch
den Starter und den erneuten Starter als Reviewer. Es bleiben die Prüfung von
Workflow, Run, Attempt, Commit und manuellem main-Start sowie die Ablehnung von
Bots, fehlenden Freigaben, falschen Environments und abgelehnten Reviews erhalten.

Die vorhandenen GitHub-Einstellungen passen bereits: Required reviewer
`stefanbeike`, Prevent self-review **aus**, ausschließlich Branch main und
kein Admin-Bypass. Unter **Review deployments → production → Approve** kannst
du deinen eigenen Lauf bestätigen. Keine GitHub-Einstellung wurde hierfür
geändert und kein Deployment gestartet.

81 Python-Tests einschließlich Selbstfreigabe- und Retry-Regressionen, 7 OIDC-
Tests und Actionlint sind erfolgreich. Nach Übernahme der Korrektur nach main
einen neuen Gesamtworkflow starten; ein Retry des alten Commits lädt weiterhin
den alten Prüfer. Bereits veröffentlichte Git-/Image-Tags niemals überschreiben.

## OIDC und AWS-IAM

AWS-Konto `070351676632`, Frankfurt `eu-central-1`. Keine Terraform-/Crossplane-
Ausführung oder automatische Infrastruktur-Einrichtung.

Die vorhandene GitHub-Rolle muss bei gemeinsamer Verwendung **genau diese beiden
Subjects** mit Audience `sts.amazonaws.com` akzeptieren:

```text
repo:stefanbeike@13099702/gratilo@1351208624:environment:release
repo:stefanbeike@13099702/gratilo@1351208624:environment:production
```

Die unveränderlichen Owner-/Repository-IDs sind Teil der tatsächlichen Identität;
kein Wechsel zum alten Subject ohne IDs. Die kombinierte Vorlage ist
`deployment/iam/github-oidc-trust-policy.json`. Die getrennten Templates
`github-oidc-release-trust.json` und `github-oidc-production-trust.json` zeigen
jeweils nur einen Subject. Für eine gemeinsame Rolle die kombinierte Variante
verwenden; keine Wildcard-Subjects. Die fünf öffentlichen OIDC-Claims werden
geprüft, der vollständige Token niemals ausgegeben.

Die am 18.10.2026 nur lesend abgefragte aktuelle Trust-Policy erlaubt noch
**ausschließlich production**. **release muss daher manuell ergänzt werden.**
Die Rolle besitzt die Inline-Policies `gratilo-github-ecr-push` und
`gratilo-github-ecs-deploy`; das allein bestätigt noch nicht jede benötigte Aktion.
Eine zusätzliche rein lesende IAM-Simulation von 33 benötigten Aktion-/Resource-
Paaren ergab sechs **implicitDeny**:

- `ecs:DescribeTaskDefinition`
- `ecs:ListTagsForResource`
- `ecs:TagResource`
- `ecs:ListTasks`
- `ecs:DescribeTasks`
- `elasticloadbalancing:DescribeTargetHealth`

Diese Rechte gemäß der untenstehenden ECS-Policy-Vorlage manuell ergänzen.
Die übrigen geprüften ECR-, Service-Update-, RegisterTaskDefinition- und beiden
PassRole-Paare waren erlaubt. Die Simulation ersetzt keine Prüfung von GitHub-
Protection Rules, OIDC-Trust oder eventuell übergeordneten SCPs.

Benötigte Berechtigungen, vorhandene lokale Vorlagen:

- `deployment/iam/github-deployment-policy.json`: ECR-Login und Push/Describe
  nur auf `gratilo/frontend`, `gratilo/user-service`, `gratilo/payment-service`.
  Alle Repositories müssen **IMMUTABLE ohne Ausnahmen** sein; der Workflow prüft
  das, ändert diese Einstellung aber nicht.
- `deployment/iam/github-ecs-deployment-policy.json`: Register/Describe/Tag der
  Familie `gratilo-prd-app:*`; Update/Describe ausschließlich des bestehenden
  Service im Cluster `gratilo-prd-cluster`; Task-Status, Target-Health und gezieltes
  `iam:PassRole` nur für `gratilo-prd-ecs-execution-role` sowie
  `gratilo-prd-ecs-task-role`, ausschließlich an `ecs-tasks.amazonaws.com`.
- Keine Anwendungs-Secret-Value-Rechte oder Secrets-Manager-Schreibrechte für
  GitHub. Credentials werden weiterhin von der ECS Execution Role injiziert.
  Keine IAM-Key-Erstellung, DNS-, Netzwerk-, RDS- oder Zertifikatsänderung.

Die gemeinsame Rolle hat die Vereinigungsmenge von ECR- und ECS-Rechten. Für eine
spätere strengere Rollentrennung kann release eine reine ECR-Rolle über seine
Environment-Variable nutzen; dann beide Rollen jeweils mit dem engen Trust-
Template konfigurieren. Der Deploy verwendet die vorgegebene bestehende Rolle.
IAM-/Trust-Änderungen sind manuelle Einrichtung, nicht Teil dieser Implementierung.

## Deployment und Funktionsprüfung

Ziel: Cluster `gratilo-prd-cluster`, Service `gratilo-prd-app`. Die bestehende
Task-Definition wird einschließlich CPU/RAM, Container-Limits, Healthchecks,
Secrets-Referenzen, Umgebungsvariablen, Tags und Sidecars übernommen. Nur die drei
Images werden auf die gerade bestätigten `repository@sha256:...` umgestellt.
Desired Count bleibt erhalten, ebenso Service-Netzwerk und ALB-Zuordnung.

Voraussetzungen: vorhandener Fargate-Service, ECS Rolling Deployment Controller,
Circuit Breaker enable+rollback, private Task-IP, frontend:80 am ip-Target,
interne Backend-Ports 8081/8082 und localhost-Kommunikation. RDS `gratilodb` mit
verify-full und gebündeltem CA, CloudWatch Logs sowie erreichbare ECR-/Secrets-/
Logs-/S3-Endpunkte oder bestehender NAT-Egress. Ressourcen werden nicht angelegt.
Details zur manuellen Laufzeiteinrichtung: [manual-ecs-production.md](manual-ecs-production.md).

Nach services-stable werden die angeforderte neue Revision und Running/Desired
nochmals geprüft. Ein stabiler automatischer Rollback zählt nicht als erfolgreicher
Deploy. Danach alle drei Container HEALTHY in der neuen Revision, gesundes
ALB-Target, gültiges TLS auf `https://gratilo.eu`, Frontend 200, User-Service-
Features mit ausgeschalteten Pools und geschützter anonymer Payment-GET 401/403.
Keine Registrierungs-, Mail- oder Zahlungsaufrufe im Deployment-Healthcheck.

Stripe und Pool-Splitting bleiben deaktiviert. SMTP-Secret und sonstige Secrets
werden nicht verändert. **Ein Image-Deployment repariert keine ungültigen SMTP-
Credentials.** Die separat bereits freigegebene und ausgeführte SMTP-Reparatur
ist im [Registrierungs-/SMTP-Bericht](registration-smtp-fix.md) dokumentiert.

## Konflikte und Wiederanlauf

Eine einzige Concurrency-Gruppe serialisiert den vollständigen Ablauf bis nach
Deployment/Approval. cancel-in-progress=false; ein laufender Release wird nicht
abgebrochen. GitHub garantiert keine FIFO-Schlange und kann ältere wartende Runs
ersetzen. Keine konkurrierenden Releases außerhalb dieses Workflows ausführen.

Vor dem ersten ECR-Push werden Remote-main, Remote-Tags und alle sechs ECR-Tags
nochmals geprüft. Existierende ECR-Tags werden nie überschrieben. Remote-main
oder Versionshistorie geändert: neuer vollständiger Lauf statt Umbenennen schon
gebauter Artefakte. Externe Rennen werden durch CREATE-only-Git-Refs und ECR-
Immutability begrenzt. Zwischen AWS und GitHub gibt es keine atomare Transaktion.

- **Vor dem ersten Push fehlgeschlagen:** Ursache korrigieren, neuen vollständigen
  manuellen Lauf starten. Er plant die Version neu und führt alle Prüfungen aus.
- **ECR teilweise oder vollständig veröffentlicht, Tag fehlt:** Workflow bleibt
  fehlgeschlagen und deployt nichts. Summary nennt bestätigte Pushes/Digests.
  Nicht blind wiederholen, löschen oder überschreiben. Fehlgeschlagene Version
  ist in ECR belegt, selbst wenn der Git-Tag fehlt. Nach Korrektur kann über einen
  freien größeren SemVer-Sprung (z.B. minor) ein neuer vollständiger Lauf erfolgen.
  Falls unbedingt ein weiterer Patch benötigt wird, zuerst eine ausdrücklich
  geprüfte administrative Recovery/Reservierung der belegten Version durchführen;
  keine automatische Tag-Erzeugung für unvollständige Releases. Auch ein Rerun
  des Publish-Jobs überschreibt keine existierenden Tags und bricht bei Konflikten ab.
- **Tag-Erstellung durch Protection verweigert:** zuerst Repository-Regel für den
  vorgesehenen Release-Aktor prüfen; sechs ECR-Referenzen können bereits bestehen.
  Kein Deployment, kein automatischer Bypass, gleiche Recovery-Regeln wie oben.
- **Publish+Tag erfolgreich, Deploy fehlgeschlagen:** bevorzugt **Re-run failed
  jobs** im selben Lauf. Kein erneuter Build/Publish; ursprüngliche geprüfte
  Digests bleiben gebunden. Production-Protection und manuelle Freigabe erneut
  beachten. Artefakte werden sieben Tage aufbewahrt.

Der Git-Tag wird mit GITHUB_TOKEN als annotiertes Objekt plus neuer Ref erstellt.
Es gibt keinen aktiven Tag-Push-Release-Trigger; auch ein anderer Token würde
keinen zweiten Release/Deploy starten. Die normalen GitHub-Regeln zur Vermeidung
rekursiver Token-Events bieten eine zusätzliche Absicherung.

## Rollback

Bei Fehlern nach dem Service-Update: ECS-Diagnosefelder ausgeben, vorherige
Task-Revision und Desired Count wiederherstellen, Stabilität und dieselben
Healthchecks prüfen. Job bleibt **fehlgeschlagen**, auch bei erfolgreichem Rollback.
Zeigt der Service inzwischen auf eine fremde Revision, wird nicht darübergerollt;
Operator-Eingriff erforderlich. Automatischer Rollback des ECS Circuit Breakers
wird erkannt. Ein nicht verifizierter Rollback erscheint deutlich in der Summary.

Ein erzwungen beendeter Runner kann keine Shell-Aufräumroutine garantieren.
Dann ECS-Events/Circuit Breaker und die in der Summary genannte vorherige Revision
prüfen und nötigenfalls nach expliziter operativer Freigabe manuell zurücksetzen.
Kein zusätzlicher GitHub-Dispatch-Workflow wird dafür angeboten. Für einen
geplanten fachlichen Rollback den Code auf main reviewt zurücknehmen und einen
neuen Patch über denselben Gesamtworkflow veröffentlichen.

Rollback setzt keine Datenbankmigrationen zurück, ändert keine Secrets und löscht
keine Daten. Liquibase-Änderungen müssen rückwärtskompatibel sein.

## Lokale Validierung und Dateiübersicht

```bash
python3 -m unittest discover -s deployment/tests -v
node --test deployment/tests/oidc_claims.test.cjs
actionlint
bash -n deployment/scripts/deploy_ecs.sh
```

Die Regressionstests mocken AWS, Docker und GitHub; sie führen keinen Release
oder Deploy aus. Sie prüfen SemVer, Main-Änderungen, Konflikte und Wiederholungen,
annotierte Tags, Publish-Outputs, Digest-Bindung, menschliche Production-Freigabe,
Fehler/403/fehlende Reviews, Task-Erhaltung, Healthchecks und Rollback einschließlich
HTTPS-Fehlern und Schutz vor fremden Revisionen. Backend-/Frontend-/PostgreSQL-
Tests und Trivy bleiben unverändert verpflichtende Gates im Gesamtworkflow.

Geändert: die vier Release-/Deploy-YAML-Dateien, SemVer-, OIDC- und Image-Auswahl-
Skripte, neue `production_approval.py`, zugehörige Tests, kombinierte/Release-OIDC-
Trust-Vorlagen und aktuelle CI/CD-Dokumentation. `ci.yml`, Docker-Build-/Scan-
Implementierung und `deploy_ecs.sh` werden unverändert wiederverwendet.
Die vor Arbeitsbeginn vorhandenen lokalen Änderungen am SMTP-Bericht bleiben
unverändert erhalten. Kein Commit, Push, Tag, AWS-Schreibzugriff, Workflow-Start,
Mailversand oder Produktionsdeployment wurde bei dieser CI/CD-Änderung ausgeführt.

Prüfergebnis dieser Änderung: **68 Python-Regressionstests**, **7 OIDC-Node-Tests**
und **Actionlint 1.7.7 für alle zehn Workflows** erfolgreich. Zusätzlich YAML-
Abhängigkeiten/Pflichtinputs, genau ein Dispatch-Einstieg, fünf Stufen und die
Freigabeprüfung vor AWS validiert; Python-AST, IAM-JSON, Shell-Syntax und
`git diff --check` erfolgreich. Kein echter Release-/Deploy-Test ausgeführt.
