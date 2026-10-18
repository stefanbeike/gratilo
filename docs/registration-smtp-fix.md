# Registrierung und SES-SMTP: Fehleranalyse und freizugebende Korrektur

Stand: 17.10.2026. Änderungen liegen lokal auf `main`. Kein Push, Release,
Secret-Update oder Produktionsdeployment wurde für diesen Fix ausgeführt.
Die vorangegangene Inbetriebnahme ist im [Deploymentbericht](deployment-v0.0.1-2026-10-17.md) dokumentiert.

## Nachgewiesene Ursache

- Produktionsstand bei Prüfung: `gratilo-prd-app:1`, alle Images `v0.0.1`,
  Service stabil mit 1/1 Tasks. ALB und Nginx erreichen die Backends.
- Der Registrierungsaufruf ist `POST /user-service/users/register`, JSON mit
  `email`, `password`, `confirmPassword` und `agbFileName`; weitere Profilfelder
  sind optional. Frontend und Reverse Proxy verwenden diesen Pfad korrekt.
- CloudWatch um **12:22:01 UTC**: `UserController.register:74` →
  `EmailVerificationService.createAndSend:43` → `EmailSendService.sendFrom:63` →
  `jakarta.mail.AuthenticationFailedException`, SMTP **535 Authentication Credentials Invalid**.
  Die unbehandelte `EmailException` wurde zu einem echten Backend-HTTP-500.
- SES-Konto: Produktionszugang und Versand aktiviert, Enforcement `HEALTHY`.
  Frankfurt-Host, Port 587, STARTTLS und JSON-Schlüsselzuordnung sind korrekt.
- Das Secret wurde ausschließlich im Speicher geprüft: Die gespeicherte
  Benutzerkennung entspricht **keiner langfristigen AWS-Access-Key-ID**;
  das Passwort hat dagegen SES-SMTP-v4-Format. Die tatsächliche TLS/AUTH-Prüfung
  dieses Paares lieferte ebenfalls **535**, ohne eine Nachricht zu senden.
  Formatkorrektheit allein beweist nicht, dass das Passwort zur richtigen ID gehört.
- Die exakte Zuordnung zu einem vorhandenen IAM-Benutzer/Access Key konnte
  nach Einschränkung des Netzwerkzugriffs nicht abschließend gelesen werden.
  Deshalb wird kein bestimmter IAM-Benutzername als gespeicherter Wert behauptet.

SES verlangt als SMTP-Benutzerkennung die Access-Key-ID des berechtigten
IAM-Benutzers, als Passwort das **regionsabhängig abgeleitete SMTP-Passwort**.
IAM-Anmeldename, Konsolenpasswort, temporäre STS-Zugangsdaten und ein
unveränderter AWS Secret Access Key sind hierfür ungeeignet.
[AWS: SES-SMTP-Zugangsdaten](https://docs.aws.amazon.com/ses/latest/dg/smtp-credentials.html).

Der User-Service verwendet **Micronaut**, nicht Spring Boot. Es sind keine
Spring-Security-Änderungen oder Änderungen an Datenbanktabellen erforderlich.

## Codeänderungen

- `EmailDeliveryExceptionHandler`: `EmailException` wird nach dem
  Transaktionsrollback zu HTTP 503 mit `EMAIL_DELIVERY_UNAVAILABLE` und einer
  verständlichen Meldung; `Cache-Control: no-store`. Providerantworten,
  Empfänger, Passwörter, Bestätigungslinks und Stacktraces werden nicht geloggt.
  Die Logkategorie unterscheidet `SMTP_AUTHENTICATION` und `DELIVERY_FAILURE`.
- Die Registrierung bleibt atomar. Bei Mailfehlern werden Benutzer,
  Bestätigungstoken und URL-Zuweisung zurückgerollt. Es wird keine erfolgreiche
  Registrierung vorgetäuscht und kein Konto automatisch bestätigt.
- Neuer Endpunkt `POST /user-service/auth/verification/resend` mit JSON
  `email` und `password`. Nur das richtige Passwort eines nicht gelöschten,
  unbestätigten Kontos kann einen Versand auslösen. Andere Fälle erhalten
  dieselbe generische 202-Antwort. Ein Row Lock und 60 Sekunden Wartezeit
  begrenzen wiederholte Versuche. Bei SMTP-Fehlern bleibt der alte Token durch
  Rollback erhalten; bei erfolgreichem Neuversand ersetzt ihn ein neuer Token.
- Frontend: `/resend-verification`, erreichbar aus dem Login. Bei HTTP 503
  bleibt das Registrierungsformular für einen späteren Versuch verfügbar.
- `logback.xml` setzt den Standard auf INFO statt unkontrollierter DEBUG-Ausgabe.
- Keine neue Liquibase-Migration, keine Aufweichung von TLS, Passwortprüfung,
  E-Mail-Bestätigung, JWT oder deaktivierten Zahlungen.

## Lokale Tests und Build

Die Regression wurde vor dem Handler-Fix reproduziert: zwei Tests erhielten
`INTERNAL_SERVER_ERROR` statt `SERVICE_UNAVAILABLE`. Danach bestehen:

- 35 User-Service- und 13 Payment-Service-Tests, einschließlich fünf echter
  HTTP-/JPA-Integrationstests mit isolierter H2-Datenbank;
- vollständiger Rollback bei SMTP-535, erneute Registrierung nach Behebung,
  BCrypt-Hash, Verifikation, JWT-Login und Zugriff auf das eigene Profil;
- bestehender Login trotz fehlgeschlagenem neuem Registrierungsversuch;
- Validierung, doppelte Registrierung, Passwortprüfung beim Neuversand,
  ausgeschlossene verifizierte/gelöschte Konten, Token-Erhalt und Wartezeit;
- 21 Frontend-Tests und Produktionsbuild;
- 43 Deployment-/SemVer-/SMTP-Tooltests und fünf OIDC-Tests;
- Workflow-Validierung mit actionlint.

Der separate Task `registrationPostgresTest` führt dieselben fünf
Integrationstests mit PostgreSQL 15 und den echten Liquibase-Migrationen aus.
Die vorhandene Test-CI ruft ihn über folgendes Skript auf:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  python3 deployment/scripts/test_registration_postgres.py
```

Das Skript erstellt ausschließlich einen temporären lokalen PostgreSQL-Container,
veröffentlicht ihn auf Loopback und entfernt ihn anschließend. Der Testprovider
akzeptiert externe Datenbank-URLs nur für `127.0.0.1` und die ausdrücklich
benannte Datenbank `gratilo_registration_test`. Mailversand ist gemockt.
Die Berichte liegen getrennt unter
`backend/user-service/build/test-results/registrationPostgresTest`.

Die fünf Tests wurden auch mit PostgreSQL 15 und den echten Migrationen
erfolgreich ausgeführt. Für Java-21-Container nutzt dieser Testtask den
expliziten Mockito-Premain-Agenten aus dem aufgelösten Test-Classpath statt
dynamischer Self-Attachment-Instrumentierung.
[Mockito: Java-21-Instrumentierung](https://github.com/mockito/mockito/blob/main/mockito-core/src/main/java/org/mockito/Mockito.java).
Das lokale Backend-Image `gratilo-registration/user-service:local` wurde mit
dem vorhandenen Dockerfile erfolgreich gebaut. Es wurde nicht veröffentlicht.
Zusätzlich wurde mit einer absichtlich fremden Standard-JDBC-URL geprüft,
dass die explizite lokale Test-URL Vorrang hat. Produktive Datenbank-URLs aus
der Shell können den Test dadurch nicht auf eine andere Datenbank umlenken.

Die beiden vorhandenen ECS-JSON-Vorlagen benötigen keine SMTP-Mapping-
Korrektur. Es wurde kein Terraform eingeführt oder ausgeführt.

## Freigabegrenze

**Alle folgenden Schreibbefehle sind vorbereitet, nicht ausgeführt.** Vor
Secret-Änderung, Credential-Rotation, Git-Push, Release und ECS-Deployment
ist die ausdrückliche Freigabe des Betreibers erforderlich. Keine neuen
SMTP-/IAM-Zugangsdaten werden automatisch angelegt.

### 1. Bestehendes Secret sicher korrigieren

Lesende Prüfung mit Ausgabe ausschließlich von Formatmerkmalen und Statuscode:

```bash
python3 deployment/scripts/smtp_secret.py audit --profile gratilo --check-auth
```

Die korrekte Access-Key-ID des bestehenden SES-SMTP-IAM-Benutzers in SES/IAM
ermitteln und ausschließlich in den verdeckten Prompt eingeben. Nicht in Chat,
Shell-Argumente, Environment-Dateien oder Logs kopieren. Falls das bestehende
SMTP-Passwort zu dieser ID gehört, reicht nach Freigabe:

```bash
python3 deployment/scripts/smtp_secret.py update --profile gratilo \
  --keep-password --confirm-write 'UPDATE gratilo/prd/smtp'
```

Wenn auch das Passwort ersetzt werden muss, `--keep-password` weglassen.
Beide Eingaben werden verdeckt abgefragt. Falls bewusst ein bestehender
langfristiger AWS Secret Access Key verwendet wird, stattdessen `--derive`
verwenden; die Umrechnung erfolgt nach dem offiziellen AWS-Verfahren für
Frankfurt im Speicher. Es wird niemals das rohe AWS-Secret als SMTP-Passwort gespeichert.

Das Werkzeug prüft Account `070351676632`, Region `eu-central-1`, Format,
verifiziertes STARTTLS und AUTH. Nur bei SMTP-Code **235** erfolgt eine neue
Version des vorhandenen Secrets; andere JSON-Schlüssel werden erhalten.
Vor dem Schreiben wird geprüft, ob die gelesene Secret-Version noch aktuell ist.
Andere Administratoren dürfen während dieses Vorgangs das Secret nicht ändern;
der Versionsvergleich ist kein atomarer Compare-and-Swap.

Der zugrunde liegende AWS-CLI-Aufruf ist `secretsmanager put-secret-value
--cli-input-json file:///dev/stdin --profile gratilo --region eu-central-1`.
Das Werkzeug erzeugt den kompletten Request im Speicher und gibt ihn nur an
stdin des CLI-Prozesses weiter. Weder SecretString noch Passwort stehen in argv,
Dateien oder Ausgabe. Auf Systemen ohne funktionierende `/dev/stdin`-Unterstützung
bricht es ab; dann denselben bestehenden Secret-Eintrag manuell in der AWS-Konsole
korrigieren. Nicht auf sichtbare Kommandozeilenargumente oder temporäre Secret-Dateien ausweichen.

Benötigte IAM-Aktionen: `secretsmanager:GetSecretValue` und nach Freigabe
`secretsmanager:PutSecretValue`, beschränkt auf das bestehende SMTP-Secret;
bei kundeneigenem KMS-Key zusätzlich die passenden KMS-Rechte. Execution-Role-
Rechte und ECS-Secret-Referenzen müssen für diesen Fix nicht erweitert werden.

Das alte Skript `aws/ses_smtp_password.py` gibt ein Passwort auf stdout aus und
soll hierfür **nicht verwendet werden**. Es wurde nicht ausgeführt. Seine
Bearbeitung war in dieser Arbeitsumgebung schreibgeschützt; das neue Werkzeug
ist der sichere Ersatz.

### 2. Bestehende Tasks nach Secret-Korrektur ersetzen

ECS injiziert Secrets beim Taskstart. Ein Secret-Update allein verändert den
laufenden Container nicht. Nach Freigabe kann die bestehende Revision mit
den korrigierten Zugangsdaten erneut gestartet werden:

```bash
aws ecs update-service --cluster gratilo-prd-cluster --service gratilo-prd-app \
  --force-new-deployment --profile gratilo --region eu-central-1 \
  --query 'service.{task:taskDefinition,desired:desiredCount,running:runningCount}'
aws ecs wait services-stable --cluster gratilo-prd-cluster --services gratilo-prd-app \
  --profile gratilo --region eu-central-1
```

Das korrigiert den operativen Credential-Fehler auch vor dem Code-Release.
Die neuen 503-/Resend-Funktionen benötigen zusätzlich das folgende Patch-Release.

### 3. Patch-Code über den vorhandenen Release-Prozess veröffentlichen

Nach Codefreigabe und Git-Push den neuen [Tag-Release-Prozess](cicd.md) verwenden.
Version zunächst anhand der tatsächlichen Remote-Tags prüfen, dann nach Freigabe:

```bash
git tag -a v0.0.2 -m 'Gratilo v0.0.2'
git push origin refs/tags/v0.0.2
```

Bei unveränderter Tag-Historie ist die nächste Version `v0.0.2`; ein bereits
existierender Tag darf nicht überschrieben werden. Alle drei Images erhalten denselben SemVer-Tag
und ihren unveränderlichen SHA-/Run-Tag. Tests, HIGH-/CRITICAL-Trivy-Gates und
die Freigabe im Environment `production` bleiben erhalten. Der Workflow
veröffentlicht ausschließlich nach ECR; er registriert oder deployt keine ECS-Task.
`v0.0.1` wird nicht überschrieben.

### 4. Neue ECS-Revision aus der tatsächlich laufenden Konfiguration vorbereiten

Für den aktuellen Regelbetrieb den separaten manuellen Deploy-Workflow aus
[cicd.md](cicd.md) verwenden. Die nachfolgenden CLI-Schritte dokumentieren den
manuellen Alternativweg und dürfen nicht parallel zu Actions ausgeführt werden.

Erst nach erfolgreichem, freigegebenem ECR-Release ausführen. Diese Vorbereitung
liest AWS und schreibt eine lokale JSON-Datei mit **Secret-Referenzen**:

```bash
python3 - <<'PY'
import json, subprocess, sys
from pathlib import Path
sys.path.insert(0, str(Path('deployment/scripts').resolve()))
from render_task_definition import render
def aws(*args):
    return json.loads(subprocess.check_output([
        'aws', *args, '--profile', 'gratilo', '--region', 'eu-central-1', '--output', 'json']))
service = aws('ecs', 'describe-services', '--cluster', 'gratilo-prd-cluster',
              '--services', 'gratilo-prd-app')['services'][0]
current = aws('ecs', 'describe-task-definition', '--task-definition', service['taskDefinition'])
images = {}
for name in ('frontend', 'user-service', 'payment-service'):
    details = aws('ecr', 'describe-images', '--repository-name', 'gratilo/' + name,
                  '--image-ids', 'imageTag=v0.0.2')['imageDetails']
    assert len(details) == 1
    images[name] = '070351676632.dkr.ecr.eu-central-1.amazonaws.com/gratilo/' + name + '@' + details[0]['imageDigest']
config = {'AWS_ACCOUNT_ID': '070351676632', 'AWS_REGION': 'eu-central-1',
          'ECS_TASK_FAMILY': 'gratilo-prd-app',
          'ECS_TASK_EXECUTION_ROLE_ARN': 'arn:aws:iam::070351676632:role/gratilo-prd-ecs-execution-role',
          'ECS_TASK_ROLE_ARN': 'arn:aws:iam::070351676632:role/gratilo-prd-ecs-task-role'}
task = render(current, images, config)
Path('/tmp/gratilo-prd-app-v0.0.2.json').write_text(json.dumps(task, indent=2) + '\n')
print('Validated task JSON prepared; only the three image references changed.')
PY
```

Falls der Release inzwischen eine andere Version hat, den konkreten Tag und
Dateinamen entsprechend ersetzen. Der bestehende Renderer erhält Secrets,
Health Checks, Abhängigkeiten, Ressourcenlimits und Umgebungsvariablen.
Er prüft unter anderem `gratilodb`, RDS-TLS und deaktivierte Zahlungen.

Die folgenden AWS-Schreibbefehle nur nach ausdrücklicher Deployment-Freigabe:

```bash
gratilo_patch_task=$(aws ecs register-task-definition \
  --cli-input-json file:///tmp/gratilo-prd-app-v0.0.2.json \
  --profile gratilo --region eu-central-1 --query taskDefinition.taskDefinitionArn --output text)
aws ecs update-service --cluster gratilo-prd-cluster --service gratilo-prd-app \
  --task-definition "$gratilo_patch_task" --profile gratilo --region eu-central-1 \
  --query 'service.{task:taskDefinition,desired:desiredCount,running:runningCount}'
aws ecs wait services-stable --cluster gratilo-prd-cluster --services gratilo-prd-app \
  --profile gratilo --region eu-central-1
```

### 5. Deployment und fachliche Funktion prüfen

```bash
aws ecs describe-services --cluster gratilo-prd-cluster --services gratilo-prd-app \
  --profile gratilo --region eu-central-1 \
  --query 'services[].{status:status,task:taskDefinition,running:runningCount,desired:desiredCount,deployments:deployments[].rolloutState}'
aws elbv2 describe-target-health \
  --target-group-arn arn:aws:elasticloadbalancing:eu-central-1:070351676632:targetgroup/gratilo-prd-frontend-tg/d3c8c1e7bc7dadaf \
  --profile gratilo --region eu-central-1
curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' https://gratilo.eu
```

Zusätzlich alle drei Container auf `HEALTHY`, erwartete Image-Digests und
ECS-Rollout `COMPLETED` prüfen. CloudWatch gezielt auf Fehlerkategorien
auswerten; keine ungeschützten kompletten Logs, Header oder Tokens ausgeben.

Ein echter Registrierungs-/Verifikations-/Login-Test benötigt einen ausdrücklich
freigegebenen, vom Betreiber kontrollierten Testempfänger und löst echte SES-Mail
aus. Keine erfundene oder fremde Empfängeradresse verwenden. Dieser Produktions-
Test wurde für den Fix noch nicht ausgeführt.

## Bestehende Registrierungsversuche

Für den belegten Fehler wird die Registrierung durch die bestehende äußere
Transaktion zurückgerollt; dies ist lokal per HTTP und Datenbank geprüft.
Nach Credential-Korrektur kann dieselbe E-Mail-Adresse erneut registriert werden.
Das ist keine pauschale Aussage über sämtliche historischen Konten: Es wurden
keine produktiven Benutzer abgefragt, verändert oder gelöscht.

Falls bereits ein unbestätigtes Konto existiert, nach Code-Deployment über
`/resend-verification` mit den bestehenden Zugangsdaten einen neuen Link anfordern.
Ein SMTP-Ausfall macht einen vorhandenen Token dabei nicht ungültig.
Verifizierte Konten können unverändert anmelden; vergessene Passwörter nutzen
den bestehenden Passwort-Reset, der ebenfalls funktionierenden Mailversand benötigt.

## Geänderte Dateien

- Backend: `AuthController.java`, `EmailDeliveryExceptionHandler.java`,
  `EmailVerificationService.java`, `UserRepository.java`,
  `EmailVerificationTokenRepository.java`, `src/main/resources/logback.xml`.
- Backend-Tests/Build: `backend/user-service/build.gradle`,
  `RegistrationIntegrationTest.java`, `application-registration-test.yml`.
- Frontend: `frontend/src/App.tsx`, `frontend/src/App.test.tsx`.
- CI/Tools: `.github/workflows/002_unit_tests.yml`,
  `deployment/scripts/test_registration_postgres.py`,
  `deployment/scripts/smtp_secret.py`, `deployment/tests/test_smtp_secret.py`.
- Dokumentation: `docs/amazon-ses.md`, `docs/manual-ecs-production.md`,
  `docs/deployment-v0.0.1-2026-10-17.md` und diese Datei.
