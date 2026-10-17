# Amazon SES SMTP für Gratilo

Diese Anleitung beschreibt die manuelle Einrichtung in der AWS-Konsole in
`eu-central-1` (Frankfurt). Sie führt keine AWS-Änderungen aus. Ausgangspunkt laut
Projektvorgabe: `gratilo.eu` ist verifiziert und der Produktionszugang genehmigt.
Diese Angaben wurden nicht gegen das AWS-Konto geprüft. Das Repository heißt
inzwischen [`stefanbeike/gratilo`](https://github.com/stefanbeike/gratilo).

## Implementierung und Absender

Der User-Service verwendet weiterhin Micronaut Email / JavaMail 2.10.1. Ein
AWS-SDK oder zusätzlicher Versanddienst ist nicht nötig. Der Payment-Service
erhält keine SMTP-Zugangsdaten. Lokal bleiben MailHog, Port 1025 und Versand
ohne Anmeldung/TLS erhalten.

| Nachricht | Absender in Produktion |
| --- | --- |
| Registrierung, Passwort-Reset, automatische Benachrichtigungen und Pool-Einladungen | `Gratilo <noreply@gratilo.eu>` |
| Allgemeine Kommunikation über `sendGeneralText` | `Gratilo <info@gratilo.eu>` |

Der bestehende interne Endpunkt `POST /user-service/internal/notifications/email`
bleibt für Systemnachrichten bestehen. Für allgemeine Kommunikation gibt es
`POST /user-service/internal/notifications/email/general`. Beide verwenden denselben
JSON-Body mit `to`, `subject`, `text` und verlangen `X-Internal-Secret`.
Clients können keinen beliebigen Absender angeben. Es gibt keine neue öffentliche
Kontaktformular-Funktion und keine automatischen Nachrichten beim Start.

Die Warnung bei 80 % Belegung des Zahlungslink-Pools geht an `info@gratilo.eu`,
ebenfalls als Systemnachricht von `noreply@gratilo.eu`. Historische
Datenbankmigrationen werden nicht umgeschrieben. Die Website- und Link-Domain ist `https://gratilo.eu`.

## Produktionskonfiguration

`MICRONAUT_ENVIRONMENTS=prd` aktiviert `application-prd.yml`:

| Variable | Vorgabe / Bedeutung |
| --- | --- |
| `MAIL_HOST` | `email-smtp.eu-central-1.amazonaws.com` |
| `MAIL_PORT` | `587` |
| `MAIL_FROM_EMAIL` | `noreply@gratilo.eu` |
| `MAIL_GENERAL_FROM_EMAIL` | `info@gratilo.eu` |
| `MAIL_FROM_NAME` / `APP_NAME` | `Gratilo` |
| `JAVAMAIL_AUTHENTICATION_USERNAME` | SES SMTP-Benutzername, erforderlich |
| `JAVAMAIL_AUTHENTICATION_PASSWORD` | SES SMTP-Passwort, erforderlich |

Das Produktionsprofil aktiviert SMTP AUTH und STARTTLS einschließlich
`mail.smtp.starttls.required=true` und Zertifikat-Hostnamenprüfung. Implizites
SMTPS ist ausgeschaltet. Ohne TLS-Unterstützung wird kein unverschlüsselter
Versand versucht. Verbindungs-, Lese- und Schreibzeitlimits betragen jeweils
10 Sekunden; JavaMail-Debugging ist ausgeschaltet. SES unterstützt STARTTLS auf
[Port 587](https://docs.aws.amazon.com/ses/latest/dg/smtp-connect.html).

Es gibt keine Ersatz-Zugangsdaten. Fehlende Produktions-Credentials führen bei
der Auflösung der SMTP-Konfiguration zu einem Konfigurationsfehler. In ECS gehören
sie ausschließlich in `secrets`, nicht als Klartext in `environment`. Im lokalen
Compose bleibt MailHog aktiv, auch wenn Produktionsvariablen in der Projekt-`.env`
stehen; für lokale Anmeldung sind wie bisher explizite Anpassungen notwendig.

## 1. SES und SMTP-Berechtigung

1. In der SES-Konsole die Region Frankfurt auswählen. Unter Verified identities
   `gratilo.eu`, den DKIM-Status und unter Account dashboard den Produktionsstatus
   sowie das Versandkontingent prüfen. Eine verifizierte Domain erlaubt beide
   Absender ohne separate Adressverifizierung; siehe
   [SES-Identitäten](https://docs.aws.amazon.com/ses/latest/dg/creating-identities.html).
2. Vorhandene SMTP-Credentials für diese Region verwenden oder unter SMTP settings
   einen dedizierten SMTP-Benutzer anlegen. Das SMTP-Passwort ist **kein** AWS Secret
   Access Key, und temporäre ECS-Rollen-Credentials sind kein Ersatz. Die Zugangsdaten
   sind regionsgebunden; siehe [SMTP-Credentials](https://docs.aws.amazon.com/ses/latest/dg/smtp-credentials.html).
3. Der SMTP-IAM-Benutzer benötigt `ses:SendRawEmail`. Beispiel einer eingeschränkten
   Versandrichtlinie, mit eigener Account-ID statt des Platzhalters:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "ses:SendRawEmail",
    "Resource": "arn:aws:ses:eu-central-1:<AWS_ACCOUNT_ID>:identity/gratilo.eu",
    "Condition": {
      "StringEquals": {
        "ses:FromAddress": ["noreply@gratilo.eu", "info@gratilo.eu"]
      }
    }
  }]
}
```

Bereits bestehende zusätzliche IAM-/Gruppenrichtlinien können die Berechtigung
weiter fassen; die effektiven Rechte gemeinsam prüfen. Details zur Begrenzung
von Identität und Absender stehen in der
[SES-IAM-Dokumentation](https://docs.aws.amazon.com/ses/latest/dg/control-user-access.html).

## 2. Secrets Manager und ECS-Rollen

1. In Secrets Manager in Frankfurt ein Secret vom Typ Other type of secret
   mit Namen etwa `gratilo/prd/smtp` anlegen. Zwei Schlüssel verwenden:
   `username` und `password`. Die echten SES SMTP-Werte nur dort eingeben.
   Weder Zugangsdaten noch heruntergeladene Credential-Dateien in Git ablegen.
2. Den vollständigen Secret-ARN inklusive des AWS-generierten Suffixes notieren.
3. Die **Task execution role** des ECS-Tasks erhält für genau diesen Secret-ARN:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "secretsmanager:GetSecretValue",
    "Resource": "arn:aws:secretsmanager:eu-central-1:<AWS_ACCOUNT_ID>:secret:gratilo/prd/smtp-<SECRET_SUFFIX>"
  }]
}
```

Bei einem kundeneigenen KMS-Schlüssel benötigt diese Rolle zusätzlich
`kms:Decrypt` für diesen Schlüssel und die Key Policy muss den Zugriff erlauben.
Die Rolle muss dem ECS-Taskdienst `ecs-tasks.amazonaws.com` vertrauen.
Die übrigen vorhandenen Execution-Role-Rechte für Images/Logs bleiben nötig.
Die Anwendung liest ausschließlich Umgebungsvariablen; die **Task role** braucht
dafür keine zusätzlichen Secrets-Manager- oder SES-API-Rechte. Sie ersetzt auch
nicht den SMTP-IAM-Benutzer. Siehe
[ECS Task execution role](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_execution_IAM_role.html).

## 3. ECS Fargate manuell konfigurieren

1. In ECS eine neue Revision der bestehenden Task Definition vorbereiten. Die
   Datei [user-service-ses.container.json](../deployment/ecs/user-service-ses.container.json)
   ist ein **Fragment**, keine vollständige Task Definition. Nur die dortigen
   `environment`- und `secrets`-Einträge in den vorhandenen `user-service`-Container
   übernehmen. Image, Ports, Datenbank-/JWT-Secrets, Logging, Healthchecks und Rollen
   beibehalten. Gleichnamige alte Einträge ersetzen, keine Duplikate erzeugen.
2. In beiden `valueFrom`-Feldern Account-ID, Secret-Namen und Suffix durch den
   tatsächlichen Secret-ARN ersetzen. Die Endungen `:username::` und `:password::`
   wählen die jeweiligen JSON-Schlüssel aus. Alte Klartext-SMTP-Credentials und
   alte `JAVAMAIL_PROPERTIES_MAIL_SMTP_*`-Overrides entfernen, damit sie den
   Produktionsvorgaben nicht widersprechen.
3. Für JSON-Schlüssel-Injektion Fargate **Linux Platform 1.4.0 oder neuer** verwenden.
   ECS lädt die Werte beim Task-Start; nach Secret-Rotation ist ein erneuter
   Task-Start/Deployment erforderlich. Details:
   [Secrets-Manager-Injektion in ECS](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/secrets-envvar-secrets-manager.html).
4. Netzwerk prüfen: ausgehendes TCP 587 zum SES-SMTP-Endpunkt, DNS-Auflösung und
   HTTPS-Zugriff des Tasks auf Secrets Manager. Private Subnetze brauchen dafür
   geeignete NAT-/VPC-Endpunkt-Anbindung; ein Secrets-Manager-Endpunkt ersetzt
   keinen SMTP-Netzwerkpfad. Security Groups und Network ACLs müssen Hin- und
   Rückverkehr zulassen.
5. Die ECS-Service-Aktualisierung erst nach eigener Prüfung und Freigabe ausführen.
   Ein neues Deployment kann bereits vorhandene Nutzeraktionen wieder echte
   System-E-Mails auslösen. In diesem Arbeitsauftrag wurde nichts ausgerollt.

Die gemeinsame Task wird ausschließlich manuell über die AWS-Konsole aktualisiert.
Der GitHub-ECS-Deploy-Job bleibt deaktiviert. Details stehen in der
[Deployment-Anleitung](aws-ecs-deployment.md).

## 4. DNS: DKIM, SPF und DMARC

DNS beim tatsächlich autoritativen Anbieter pflegen. Route 53 ist nur zuständig,
wenn die Domain dorthin delegiert ist. Bestehende Mailanbieter und MX-Records
für eingehende E-Mails beibehalten.

- **DKIM:** Die von SES für `gratilo.eu` angezeigten CNAMEs unverändert eintragen
  (bei Easy DKIM üblicherweise drei), anschließend Status Successful prüfen.
  Keine Tokens erfinden. DKIM muss zur sichtbaren Absenderdomain passen.
- **Optionales Custom MAIL FROM:** In SES beispielsweise `bounce.gratilo.eu`
  auswählen. Dafür die von SES angezeigten Werte verwenden. Für Frankfurt sind
  das üblicherweise MX `10 feedback-smtp.eu-central-1.amazonses.com` und TXT
  `v=spf1 include:amazonses.com ~all` unter **bounce.gratilo.eu**. Nicht den
  Apex-MX von `gratilo.eu` überschreiben. Pro Name nur einen SPF-Record verwenden;
  bestehende SPF-Regeln anderer Anbieter nicht durch einen zweiten Record ergänzen.
  Siehe [Custom MAIL FROM](https://docs.aws.amazon.com/ses/latest/dg/mail-from.html).
- **DMARC:** Einen bestehenden `_dmarc.gratilo.eu`-Record prüfen. Falls noch keiner
  existiert, kann die Einführung mit TXT `v=DMARC1; p=none; adkim=r; aspf=r`
  beginnen. Eine vorhandene strengere Policy nicht abschwächen. Ein `rua`-Ziel nur
  ergänzen, wenn ein funktionierendes Postfach für Reports eingerichtet ist.
  Später anhand der Ergebnisse auf `quarantine`/`reject` verschärfen. Mit dem
  standardmäßigen SES MAIL FROM erfolgt DMARC-Alignment über DKIM; ein eigenes
  MAIL FROM ermöglicht zusätzlich SPF-Alignment. Siehe
  [DMARC bei SES](https://docs.aws.amazon.com/ses/latest/dg/send-email-authentication-dmarc.html).

SES-Domainverifizierung und SMTP-Versand richten kein Postfach für eingehende
Antworten ein. `info@gratilo.eu` benötigt für Antworten einen vorhandenen
Mailanbieter bzw. eine separat eingerichtete Empfangslösung. Der Custom-MAIL-FROM-
MX ist kein Ersatz dafür.

## Prüfung ohne echten Versand

```shell
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :backend:user-service:test :backend:payment-service:test
```

Die E-Mail-Tests verwenden einen gemockten `EmailSender`. Sie prüfen beide
Absender, Text/Links, interne Autorisierung sowie die lokale und produktive
SMTP-Konfiguration mit ausschließlich erfundenen Testwerten. Sie bauen keine
SMTP-Verbindung auf. Keine Credentials in Logs ausgeben und kein JavaMail-
Protokolldebugging aktivieren. Insbesondere nicht die komplette Task-Umgebung
oder Secret-Werte zur Fehlersuche ausgeben.

Ein späterer Ende-zu-Ende-Versand an einen ausdrücklich freigegebenen Empfänger
ist separat freizugeben. Auch Registrierung/Passwort-Reset gegen die produktiv
konfigurierte Anwendung sind echte Versandtests und wurden hier nicht ausgeführt.

Aktueller Betriebsweg: [manueller ECS-Betrieb](manual-ecs-production.md).
Das vorhandene SMTP-Secret heißt `gratilo/prd/smtp` mit `username` und `password`;
kein zusätzliches SES-Secret anlegen. Der ECS-Deploy-Job bleibt deaktiviert.

Bei SMTP 535 zuerst die Benutzerkennung prüfen: `username` muss die
Access-Key-ID des SES-SMTP-IAM-Benutzers sein. Das zugehörige SMTP-Passwort ist
regionsgebunden. Diagnose ohne Mailversand, sichere Secret-Korrektur und
erneuter Versand von Bestätigungs-E-Mails sind im
[Registrierungs-/SMTP-Fix](registration-smtp-fix.md) beschrieben.
