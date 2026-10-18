# Gratilo CI/CD: Tag-Release und manuelles Produktionsdeployment

## Ablauf

`release.yml` startet bei einem Tag-Push `v*.*.*`. `006_release_tag.yml` prüft
anschließend strikt `vMAJOR.MINOR.PATCH` (keine führenden Nullen, Prereleases oder
Build-Metadaten) und verlangt, dass der Commit zur Historie von `origin/main`
gehört. Fehlerhafte Tags beenden den Lauf vor CI/Build/Veröffentlichung.

Danach laufen die bestehenden Prüfungen aus `ci.yml`: Python-Deployment-Tests,
OIDC-Tests, Actionlint, Backend-Tests mit Java 21, Frontend-Tests und -Build,
Registrierungsregression mit isoliertem PostgreSQL 15 und gemocktem SMTP,
Source-Security-Scan und optional der konfigurierte Sonar-Gate.
`005_docker_build.yml` baut genau einmal alle drei Linux/amd64-Images, scannt
jedes mit Trivy (HIGH/CRITICAL blockieren, einschließlich ungefixter Findings)
und prüft das gemeinsame Container-Netz mit PostgreSQL 15. Die geprüften Images
werden als kurzlebige GitHub-Artefakte übergeben, nicht erneut gebaut.

`007_deploy_ecs.yml` bleibt trotz historischem Dateinamen **ausschließlich ECR-
Publisher**. Nach der Freigabe im geschützten Environment `production` nutzt er
OIDC und pusht je Image zwei Tags mit identischem, geprüftem Digest:

- `070351676632.dkr.ecr.eu-central-1.amazonaws.com/gratilo/frontend:vX.Y.Z`
- `070351676632.dkr.ecr.eu-central-1.amazonaws.com/gratilo/user-service:vX.Y.Z`
- `070351676632.dkr.ecr.eu-central-1.amazonaws.com/gratilo/payment-service:vX.Y.Z`
- Zusätzlich jeweils `sha-<40-stellige Commit-SHA>-<Run-ID>-<Attempt>`.

Kein `latest`, kein ECS-Deployment, kein Git-Tag-Schreiben im Release-Workflow.
Die bisherige manuelle Release-Eingabe `version_bump`/`PUBLISH gratilo-sandbox`
entfällt: Der vom Betreiber gepushte Git-Tag bestimmt die Version verbindlich.
Die vorhandene numerische SemVer-Berechnung bleibt im getesteten Python-Modul
für Versionsplanung erhalten; keine Versionsnummer wird in mehrere Dateien kopiert.

Ein erfolgreicher Release wird **nicht** mit dem Deploy-Workflow verkettet.
`deploy-production.yml` besitzt ausschließlich `workflow_dispatch`, verlangt
`main`, eine veröffentlichte Version und `DEPLOY gratilo-production` und nutzt
ebenfalls das Environment `production`. Ein Tag-Push kann diesen Workflow nicht
starten. Pull Requests bekommen CI und Containerprüfungen ohne AWS-OIDC-Rechte.

## GitHub-Einstellungen vor dem ersten Lauf

Repository: `stefanbeike/gratilo`. Repository Variable:

| Name | Wert |
|---|---|
| `ECR_PUBLISH_ENABLED` | `true` nach Prüfung der manuellen AWS-/GitHub-Einrichtung |

Environment **production**, Variables:

| Name | Wert |
|---|---|
| `AWS_ACCOUNT_ID` | `070351676632` |
| `AWS_REGION` | `eu-central-1` |
| `AWS_DEPLOY_ROLE_ARN` | `arn:aws:iam::070351676632:role/gratilo-github-actions-role` |

Keine neuen GitHub Secrets erforderlich. Keine AWS-Access-Keys, Stripe-Keys,
SMTP-Zugangsdaten oder Datenbankpasswörter in GitHub hinterlegen. Bereits optional
konfigurierte Sonar-Variablen `SONAR_PROJECT_KEY`, `SONAR_ORGANIZATION` und Secrets
`SONAR_TOKEN`, `SONAR_HOST_URL` bleiben unverändert.

Environment Protection Rules:

1. **Required reviewers** aktivieren, optional Self-review und Administrator-Bypass
   ausschließen. Ohne Reviewer-Regel ist ein Environment allein keine manuelle
   Freigabe. Tarif/Repository-Sichtbarkeit auf Unterstützung dieser Funktion prüfen.
2. **Selected branches and tags**: explizite **Branch-Regel `main`** und separate
   **Tag-Regel `v*`** anlegen. Die bisherige Beschränkung ausschließlich auf main
   blockiert sonst den ECR-Publisher eines Tag-Laufs. Der Deploy-Workflow selbst
   erlaubt weiterhin ausschließlich main.
3. main schützen; Tag-Ruleset für `v*`: Erstellen nur durch vertrauenswürdige
   Release-Verantwortliche, Update und Löschen sperren. Workflow-Codeänderungen
   reviewen. Eine Tag-Ref kann eigenen Workflow-Code ausführen; Ancestry-Prüfung
   allein ersetzt diese Repository-Schutzregeln nicht.
4. Actions und erforderliche Marketplace-Actions erlauben; Default-GitHub-Token
   darf read-only bleiben. Kein Job benötigt `contents: write`. Nur die beiden
   AWS nutzenden Jobs erhalten `id-token: write`.

Die Release-Pipeline startet automatisch, wartet aber weiterhin vor AWS-Zugriff
auf die bestehende Environment-Freigabe. Artefakte bleiben sieben Tage verfügbar;
danach vor dem ersten Push einen vollständigen neuen Lauf mit Tests/Build starten.
GitHub-Concurrency verhindert gleichzeitige Releases bzw. Deployments mit getrennten
Gruppen. GitHub garantiert keine FIFO-Warteschlange und kann ältere wartende Läufe
durch neuere ersetzen; ausgelassene Releases ausdrücklich nachholen.

[GitHub: Environment-Regeln für Branches und Tags](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments).

## OIDC und IAM

Der tatsächliche OIDC-Subject dieses Repositorys enthält unveränderliche IDs:

```text
repo:stefanbeike@13099702/gratilo@1351208624:environment:production
```

Audience ist `sts.amazonaws.com`. Die vorhandene Vorlage
`deployment/iam/github-oidc-production-trust.json` bleibt gültig, da beide Jobs
`environment: production` verwenden. Ein ausschließlich auf den Branch-main-
Subject begrenzter Trust genügt nicht. Der Environment-Subject ist für Tag und
main identisch; die Ref-Beschränkungen setzen Environment und Workflow um.
Keine Wildcard-Trust-Erweiterung vornehmen. Die Diagnose protokolliert nur
`sub`, `aud`, `repository`, `ref`, `environment`, niemals den Token. Beim Publisher
wird die exakte Tag-Ref geprüft, beim Deploy ausschließlich main.

Manuell an der bestehenden Rolle prüfen/ergänzen:

- `deployment/iam/github-deployment-policy.json`: vorhandene ECR-Login-/Push-/
  Describe-Rechte ausschließlich für die drei Gratilo-Repositories.
- `deployment/iam/github-ecs-deployment-policy.json`: zusätzliche ECS-Rechte
  für den einen Service, die Familie `gratilo-prd-app:*`, bestehende Task-Tags,
  Task-Status im Cluster und lesende ALB-Target-Health-Abfragen. `iam:PassRole`
  ausschließlich für Execution Role und Task Role, nur an `ecs-tasks.amazonaws.com`.
  Die Policy enthält keine Ressourcenanlage außer neuer Task-Definition-Revisionen,
  keine Infrastruktur-Löschrechte und keinen Secret-Value-Zugriff.

`ecs:ListTasks` ist auf den Cluster per Condition begrenzt; die lesende ALB-API
`DescribeTargetHealth` benötigt Resource `*`. Task-Definition-Rechte sind auf die
Familie begrenzt. `ecs:TagResource` ist nur für `RegisterTaskDefinition` erlaubt.
Die bestehende gemeinsame GitHub-Rolle hat damit die Vereinigungsmenge von ECR-
und ECS-Rechten. Eine spätere Rollentrennung kann die Publisher-Rechte zusätzlich
isolieren, ist für diesen Ausbau aber keine neue Infrastrukturvoraussetzung.

Live-Prüfung der angehängten/Inline-Policies wurde versucht, scheiterte in dieser
Sitzung mit `Could not connect to the endpoint URL: https://iam.amazonaws.com/`.
**Die aktuell wirksamen Rechte sind deshalb nicht bestätigt.** In der AWS-Konsole
Policies, Permissions Boundary und eventuelle SCPs vor dem ersten Deploy prüfen;
Vorlagen werden hier nicht automatisch angehängt. Ein gegebenenfalls verwendeter
IAM Policy Simulator ist nur ein zusätzlicher Test, keine vollständige Prüfung
von Trust, SCPs oder GitHub-Environment-Regeln.

[AWS: ECS-Aktionen und Resource-Scope](https://docs.aws.amazon.com/service-authorization/latest/reference/list_ecs.html),
[Tag-on-create](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/supported-iam-actions-tagging.html).

## AWS-Laufzeitvoraussetzungen

- Drei ECR-Repositories müssen **IMMUTABLE ohne Ausnahmen** sein. Sowohl der
  Publisher als auch die manuelle Versionsauswahl prüfen dies lesend.
- Bestehender ECS-Service `gratilo-prd-app` im Cluster `gratilo-prd-cluster`,
  rolling ECS Deployment Controller, Circuit Breaker **enable + rollback**,
  Fargate Linux 1.4.0+, ALB-Zuordnung frontend:80 und `ip`-Target.
- Gemeinsame Familie `gratilo-prd-app`: 1 vCPU / 3072 MiB, frontend:80,
  user-service:8081, payment-service:8082, interne Kommunikation localhost.
  Alle Container brauchen ECS-Healthchecks und awslogs; frontend wartet auf
  beide gesunden Backends. Vorhandene Limits, Umgebungsvariablen, Secret-
  Referenzen, Sidecars, Task-Tags, Netzwerk und ALB-Konfiguration werden erhalten.
- Private Subnetze ohne öffentliche Task-IP, RDS über private SG-Verbindung,
  Zugriff auf ECR, Logs und Secrets Manager über vorhandenen NAT-Egress oder
  ECR-api/dkr-, Logs-, Secrets-Manager- und S3-Gateway-Endpunkte. SES-SMTP benötigt
  separaten erreichbaren SMTP-Egress bzw. einen geeigneten Endpunkt.
- RDS `gratilodb`, Schemas `user_mgmt`/`payment_mgmt`, JDBC `sslmode=verify-full`
  mit `/app/rds-ca-bundle.pem`. Keine Secret-Werte im Task-JSON.
- Die Execution Role darf die referenzierten JSON-Secrets `gratilo/prd/database`,
  `gratilo/prd/smtp`, `gratilo/prd/jwt`, `gratilo/prd/internal-api` lesen; bei eigenem
  KMS-Key zusätzlich gezieltes `kms:Decrypt`. Gemeinsames JWT und internes Secret
  in beiden Backends. Die Task Role bleibt davon getrennt.
- `https://gratilo.eu` muss einschließlich gültigem TLS vom GitHub-Runner
  erreichbar sein. Kein pauschales Abschalten von HTTPS-/Hostnamenprüfung.

Der Renderer prüft den vorhandenen Produktionszustand vor jeder Registrierung
und ersetzt nur die drei Images durch `repository@sha256:...`. Bei veralteter
Task-Konfiguration bricht er mit einem Konfigurationshinweis ab. Insbesondere
legacy SMTP-Overrides sind zuerst kontrolliert mit der vorhandenen prd-Mail-
Konfiguration abzugleichen; die Pipeline korrigiert Produktionsvariablen nicht
still nebenbei. Details: [manueller Laufzeitbetrieb](manual-ecs-production.md).
Stripe und Pool-Splitting bleiben aus; keine Stripe-Secrets erforderlich.

## Einen neuen Release veröffentlichen

Erst nach Review/Freigabe der Codeänderungen und dem ausdrücklich freigegebenen
Push dieser Pipeline nach main. Folgende Beispiele **nicht** unbesehen ausführen:
Sie erzeugen/pushen einen Release-Tag und wurden bei der Implementierung nicht ausgeführt.

```bash
git switch main
git pull --ff-only
git fetch origin --tags
python3 -c 'import subprocess,sys; sys.path.insert(0,"deployment/scripts"); from semver_release import next_version; print(next_version(subprocess.check_output(["git","tag","--list"],text=True).splitlines(),"patch")["version"])'
# Ausgabe prüfen; v0.0.2 nur verwenden, falls das tatsächlich die freie nächste Version ist:
git tag -a v0.0.2 -m 'Gratilo v0.0.2'
git push origin refs/tags/v0.0.2
```

SemVer basiert auf der numerisch höchsten stabilen Git-Version, Startwert v0.0.0;
`patch`, `minor`, `major` sind für die lokale Berechnung möglich. Eine geplante
Version kann durch zwischenzeitliche Releases belegt sein: vor dem Tag-Push
erneut Remote-Tags prüfen, nie erzwingen oder verschieben. Mit dem Repository-
`GITHUB_TOKEN` erzeugte Tags starten normalerweise keinen neuen Push-Workflow;
den Tag deshalb hier mit der normalen Betreiber-Git-Authentifizierung pushen.
[GitHub: Vermeidung rekursiver Workflow-Trigger](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow).

In Actions den automatisch gestarteten ECR-Release verfolgen. Alle Tests und
Scans müssen erfolgreich sein. Den wartenden Publish-Job nach Prüfung über
**Review deployments → production** freigeben. Die Summary enthält Version,
Commit, beide Image-Tags pro Service, Digests und bestätigte Pushes.

Die vorhandene Git-Ref wird vor dem ersten Push und nach den Pushes erneut gegen
den getesteten Commit geprüft, auch bei annotierten Tags. Existiert einer der
ECR-Tags bereits, bricht der Lauf ab, statt etwas zu überschreiben. Ein Rerun nach
erfolgreicher oder teilweiser Veröffentlichung darf keine vorhandene Version
ersetzen. Bei Teilveröffentlichung die Summary prüfen, nichts automatisch löschen
und nach Korrektur einen neuen Patch-Tag auf einen geprüften main-Commit setzen.
Die drei SemVer-Tags sind erst dann als freigegebener Release nutzbar, wenn der
Release-Lauf vollständig erfolgreich war; die Deploy-Auswahl verlangt zusätzlich
die gemeinsame Commit/Run/Attempt-Provenienz aller drei Digests.

## Eine Version nach Production deployen

1. Erfolgreichen ECR-Release und Digests prüfen.
2. Actions → **Gratilo Production Deployment (manual)** → **Run workflow**.
3. Branch **main**, Version exakt beispielsweise `v0.0.2`, Bestätigung
   **DEPLOY gratilo-production** eingeben.
4. Die separate Environment-Freigabe für `production` erteilen.
5. Die Auswahl prüft alle drei vorhandenen Images, identische Build-Provenienz
   und Übereinstimmung mit dem Commit des ausgewählten Git-Tags aus main. Sie
   pinnt Digests. Vor der Registrierung werden die Tags erneut auf dieselben
   Digests geprüft. Es werden keine Images neu gebaut oder veröffentlicht.
6. Der Deploy liest die aktuelle Task-Definition inklusive Tags, validiert sie,
   registriert eine neue Revision und aktualisiert nur den vorhandenen Service.
   Die bisherige Desired Count bleibt erhalten. Die Summary enthält alte/neue
   Revision und Version; `services-stable` allein genügt nicht: angeforderte
   Revision, Running/Desired und abgeschlossener Rollout werden ausdrücklich geprüft.
7. Anschließend: alle drei Container HEALTHY in der ausgewählten Revision,
   ALB-Target gesund, HTTPS-Frontend 200, `/user-service/features` mit deaktivierten
   Pools, `/payment-service/transactions` ohne Anmeldung 401/403. Nur lesende
   Requests; keine Registrierung, Mail, Login oder Zahlung.

## Rollback und Fehlerdiagnose

Bei Fehlern nach dem ersten Service-Update: sichere ECS-Events/Stop-Gründe
ausgeben, anschließend vorherige Revision mit vorheriger Desired Count
wiederherstellen, auf Stabilität warten und dieselben Health-/HTTPS-Prüfungen
ausführen. Ein durch ECS Circuit Breaker bereits erfolgter Rollback wird erkannt.
Ein stabiler Rollback zählt **nicht** als erfolgreicher Deploy; der Job bleibt rot.
Zeigt der Service inzwischen auf eine fremde Revision, wird der automatische
Rollback verweigert, um eine außerhalb von Actions erfolgte Änderung nicht zu
überschreiben. Ein fehlgeschlagener Rollback wird deutlich in Log/Summary gemeldet.

Gezielter späterer Rollback: denselben manuellen Deploy-Workflow auf main starten
und eine frühere vollständig veröffentlichte SemVer-Version auswählen. Das erzeugt
eine neue Revision mit den alten Image-Digests und der aktuellen Konfiguration.
Bei abgebrochenem Runner/Job oder erzwungenem Timeout ist die Shell-Aufräumroutine
nicht garantiert; dann ECS Circuit Breaker/Service-Events prüfen und gegebenenfalls
die in der Summary genannte alte Task-Revision manuell wiederherstellen.

Rollback betrifft Images/Task-Revisionen, **nicht** Datenbankschemata oder Daten.
Liquibase-Migrationen müssen rückwärtskompatibel sein; keine Datenbank-Downgrades,
Secret-Rotationen oder Löschungen durch diese Pipeline. Ein defektes SMTP-Secret
wird durch ein Image-Rollback nicht repariert.

Typische Blocker: production erlaubt Tags nicht; OIDC-Subject ohne IDs; fehlende
ECS-/PassRole-/TagResource-Rechte; Mutable ECR; fehlende Image-Version oder gemischte
Builds; fehlender Circuit Breaker; ungültige JDBC-/Secret-/SMTP-Konfiguration;
private Netzwerk-Egress-Probleme; unerreichbares öffentliches HTTPS. Keine
vollständigen Task-Umgebungen, Secrets oder CloudWatch-Anwendungslogs werden durch
die Deploy-Skripte ausgegeben. AWS-Fehlertexte/Events können betriebliche Details
enthalten; Anwendungslogs und Konfigurationen dürfen selbst keine Secrets enthalten.

## Lokale Validierung und Grenzen

Auszuführen ohne AWS-Änderungen:

```bash
python3 -m unittest discover -s deployment/tests -v
node --test deployment/tests/oidc_claims.test.cjs
actionlint
bash -n deployment/scripts/deploy_ecs.sh
cd frontend && npm test && npm run build
```

Die Pipeline-Tests mocken AWS, Docker und curl. Sie prüfen Versionen, annotierte
Tags, main-Ancestry, Konflikte/Reruns, sechs unveränderliche Image-Referenzen,
gemeinsame Image-Provenienz, Task-Erhaltung, ECS-Stabilität, Container-/HTTPS-
Fehler, verifizierten Rollback und Schutz vor fremden Revisionen. Es wird dabei
kein produktiver Deploy gestartet. Die bestehende CI behält die vollständigen
Backend-, PostgreSQL- und Drei-Container-Tests bei.

### Ergebnis dieser Implementierung am 17.10.2026

- Arbeitsbeginn auf main `dee2e57`, Arbeitsbaum sauber. Bereits vorhandene Mail-
  und HTTPS-Vorbereitungen sind in diesem Commit enthalten und wurden erhalten.
- 57 Python-Tests erfolgreich, einschließlich bestehender SemVer-, ECS- und SMTP-
  Tests und neuer Tag-/Auswahl-/Rollback-Regressionen. OIDC-Node-Tests erfolgreich.
- Alle 10 aktiven Workflows mit Actionlint 1.7.7 erfolgreich geprüft. Zusätzlich
  YAML geparst, Job-Abhängigkeiten auf Zyklen/fehlende Jobs geprüft, Pflichtinputs
  wiederverwendbarer Workflows und Trennung der Deploy-Trigger validiert.
- 21 Frontend-Tests und Produktionsbuild erfolgreich. Shell-Syntax, Python-AST,
  IAM-JSON und `git diff --check` erfolgreich.
- Die vorhandene lokale v0.0.1-Task-Datei wurde mit dem unveränderten Renderer
  geprüft und akzeptiert; dies ersetzt keine aktuelle Live-ECS-Prüfung.
- Backend-Rerun blockiert: Gradle-Wrapper kann in dieser Sitzung nicht herunterladen;
  auch die vorhandene lokale Gradle-8.10-Installation scheitert beim Java-21-
  Daemonstart mit `java.net.SocketException: Operation not permitted`.
- Container-/PostgreSQL-Rerun blockiert: kein Zugriff auf `/var/run/docker.sock`.
  Keine neuen lokalen Docker-Builds oder Trivy-Image-Scans behauptet. Die unverändert
  verpflichtenden CI-Gates führen diese Prüfungen vor jeder Veröffentlichung aus.
- Live-IAM-Prüfung wegen nicht erreichbarem IAM-Endpunkt offen; Environment-
  Einstellungen und zusätzliche Rechte müssen manuell bestätigt werden.
- Keine AWS-Schreibzugriffe, Secrets gelesen, Mails/Zahlungen ausgelöst, Git-Tags
  erstellt, Commits gepusht, GitHub-Läufe gestartet oder produktiv deployed.

### Geänderte Dateien

| Bereich | Dateien relativ zum Repository |
|---|---|
| Workflows | `.github/workflows/release.yml`, `005_docker_build.yml`, `006_release_tag.yml`, `007_deploy_ecs.yml`, neu `deploy-production.yml` im selben Verzeichnis |
| Skripte | `deployment/scripts/semver_release.py`, `oidc_claims.cjs`, `deploy_ecs.sh`, neu `resolve_release_images.py`, neu `verify_ecs_health.py` im selben Verzeichnis |
| Tests | `deployment/tests/test_ecs_deployment.py`, `oidc_claims.test.cjs`, neu `test_tag_pipeline.py` im selben Verzeichnis |
| IAM | neu `deployment/iam/github-ecs-deployment-policy.json` |
| Dokumentation | `README.md`, neu `docs/cicd.md`, `docs/ecr-release.md`, `docs/aws-ecs-deployment.md`, `docs/manual-ecs-production.md`, `docs/oidc-troubleshooting.md`, `docs/registration-smtp-fix.md`, `docs/amazon-ses.md` |

`ci.yml` wurde geprüft und bleibt unverändert: seine bestehenden Tests, Security-
Gates und Workflow-Validierung werden vom Tag-Release wiederverwendet. Der aktive
Release ruft keine alte Git-Tag-Schreibfunktion des SemVer-Moduls auf; die bisherige
Logik und ihre Regressionstests bleiben zur Kompatibilität erhalten.
