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
   Wartet am Environment **production** auf eine unabhängige Freigabe. Erst
   danach liest der Job die Review-Historie und fordert AWS-Credentials an.
   ECR-Digests und Build-Provenienz müssen exakt den Outputs dieses Publish-Jobs
   entsprechen. Vorhandene Task klonen, nur die drei Images ändern, neue Revision
   registrieren und bestehenden Service kontrolliert aktualisieren.

Der Produktionsjob bleibt ohne nachgewiesene unabhängige Freigabe gesperrt.
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

- **release** benötigt keine Required reviewers: Tests/Scans und expliziter Start
  schützen die Veröffentlichung; die unabhängige Produktionsfreigabe folgt danach.
- **production**: Required reviewers, **Prevent self-review**, kein Administrator-
  Bypass. Mindestens eine andere berechtigte Person als der Starter bzw. ein
  erneuter Starter des Laufs muss freigeben. main und Änderungen an Workflows schützen.
- Der Deploy prüft die GitHub-Review-Historie desselben Runs zusätzlich:
  menschlicher Reviewer, Environment production, Freigabe, andere Identität als
  Starter/Retry-Starter, passender Workflow/Commit/Run/Attempt. Ablehnung, fehlende
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
  Digests bleiben gebunden. Production-Protection und unabhängige Freigabe erneut
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
annotierte Tags, Publish-Outputs, Digest-Bindung, unabhängige menschliche Freigabe,
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
