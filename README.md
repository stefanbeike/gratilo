# Gratilo
A web-based platform for digital tipping and personal micropayments via QR codes. Scan, tip, pay. No app or account required.

## Modules

- `backend/user-service` – Micronaut service, port `8081`
- `backend/payment-service` – Micronaut service, port `8082`
- `frontend` – React/TypeScript frontend with Vite, port `3000`

## Prerequisites

- Java 21
- Node.js 20 or newer with npm

## Build and test

Build and test the complete monorepo through Gradle:

```shell
./gradlew build
```

Run a backend service:

```shell
./gradlew :backend:user-service:run
```

Start the frontend development server:

```shell
cd frontend
npm install
npm run dev
```

## Backend in IntelliJ IDEA debuggen

Die geteilten Startkonfigurationen in `.run/` entsprechen dem Gradle-Run-Muster
und werden von IntelliJ beim Öffnen des Projekts eingelesen:

- `Gratilo user-service (Local Debug)` – Port `8081`
- `Gratilo payment-service (Local Debug)` – Port `8082`

1. Projekt als Gradle-Projekt öffnen bzw. im Gradle-Fenster neu laden. Für das
   Gradle-JVM und die Java-Toolchain ein JDK 21 konfigurieren.
2. Infrastruktur starten: `./docker-compose/start_infra.sh`. Unter Windows den
   Befehl in WSL/Git Bash ausführen oder im Projektverzeichnis
   `docker compose -f docker-compose/docker-compose.infra.yml up -d` verwenden,
   nachdem das Netzwerk `gratilo-network` einmal angelegt wurde.
3. Falls der vollständige Stack läuft, die konkurrierenden Anwendungscontainer
   stoppen, damit die Ports für die IDE frei sind:
   `docker compose -f docker-compose/docker-compose.infra.yml -f docker-compose/docker-compose.services.yml stop user-service payment-service frontend`.
4. Oben in IntelliJ die gewünschte Startkonfiguration auswählen und **Debug**
   (Käfer-Symbol) starten. Beide Services können gleichzeitig laufen. Breakpoints
   direkt im Java-Code setzen; **Run** startet dieselbe Konfiguration ohne Debugger.
5. Für das Frontend in einem separaten Terminal `cd frontend`, `npm install`
   und `npm run dev` ausführen. `http://localhost:3000` leitet User-Service-Aufrufe
   über den Vite-Proxy an den lokal gestarteten Service auf Port `8081` weiter.

Die vorhandenen `application.yml`-Defaults verwenden PostgreSQL unter
`localhost:5432/gratilodb` (Benutzer `postgres`, Passwort `dbpass`) und MailHog
unter `localhost:1025`. Die Datenbankschemas werden durch die Infrastruktur
angelegt, die Migrationen laufen beim Service-Start. Docker-Hostnamen wie
`postgres` oder `mailhog` dürfen für den IDE-Start nicht als Umgebungsvariablen
übernommen werden. Die Startkonfigurationen benötigen keine zusätzlichen Secrets.

Prüfen: `http://localhost:8081/health` und `http://localhost:8082/health`.
Test-E-Mails sind unter `http://localhost:8025` erreichbar.
MailHog benötigt keine SMTP-Anmeldung. Für einen SMTP-Server mit Anmeldung
`MAIL_SMTP_AUTH=true`, `JAVAMAIL_AUTHENTICATION_USERNAME` und
`JAVAMAIL_AUTHENTICATION_PASSWORD` setzen; bei Bedarf zusätzlich
`MAIL_SMTP_STARTTLS_ENABLE=true`, `MAIL_HOST` und `MAIL_PORT` konfigurieren.
Alternativ starten die Gradle-Tasks `:backend:user-service:run` und
`:backend:payment-service:run` die Services auch über das Gradle-Fenster bzw.
`./gradlew` (Windows: `gradlew.bat`).

## Docker Compose

### Stripe-Testmodus konfigurieren

Lege im Repository-Stamm eine lokale Datei `.env` an (die Datei ist per
`.gitignore` ausgeschlossen) und übernimm die Variablen aus `.env.example`.
Verwende ausschließlich Testschlüssel aus dem Stripe-Dashboard. Der
`STRIPE_SECRET_KEY` wird nur an den Payment-Service gegeben. Stripe-hosted Connect-Onboarding
und Stripe Checkout benötigen keinen Publishable Key im Frontend. Ein
`STRIPE_WEBHOOK_SECRET` ist bei aktivierten Zahlungen für die Signaturprüfung erforderlich.
Zahlungen bleiben zunächst ausgeschaltet. Für einen lokalen Testlauf explizit
`PAYMENTS_ENABLED=true` und `VITE_PAYMENTS_ENABLED=true` setzen; das passende
`sk_test_…` und `whsec_…` verwenden.
`STRIPE_TEST_ONLY=true`, `STRIPE_SANDBOX_PAYOUTS=true` und
`FEATURE_TIP_POOLS_ENABLED=false` müssen erhalten bleiben. Die Anwendung bricht
bei Live-Schlüsseln oder aktivierten Pools den Start ab.
Danach den Stack mit `--build` neu bauen:

```shell
./docker-compose/start_gratilo.sh --build
```

The local setup separates infrastructure and application services. It starts
PostgreSQL, MinIO and MailHog without an external payment provider or public
webhook tunnel.

Start only the infrastructure:

```shell
./docker-compose/start_infra.sh
```

Build and start the complete stack:

```shell
./docker-compose/start_gratilo.sh --build
```

Stop the stack while keeping its data:

```shell
./docker-compose/stop_gratilo.sh
```

The frontend is available at `http://localhost:3000`. MailHog runs at
`http://localhost:8025` and the MinIO console at `http://localhost:9001`.

## Frontend authentication

Responsive pages: `/login`, `/register`, `/forgot-password`, `/reset-password?token=...`
and `/auth/verify?token=...`. Registration sends a verification email; open it in
MailHog and confirm the address before logging in. Password reset uses the link
from a second email. Login retrieves the authenticated profile; the token stays
in memory, so reloading the page requires signing in again.

The frontend proxies `/user-service/` to the backend through Nginx in Compose
and through Vite during local development. No browser-side API hostname is needed.
To test email links on a phone or tablet on the same network, start with your
computer's reachable LAN address:

```shell
FRONTEND_BASE_URL=http://192.168.1.100:3000 ./docker-compose/start_gratilo.sh --build
```

Replace the example IP with your computer's address and open that address on the
device. MailHog is available on the same IP at port 8025.
Registration includes clearly marked local test terms (`agb-demo-v1`) and a test
privacy notice (`privacy-demo-v1`); replace these with real documents before public use.

## Amazon SES für Produktions-E-Mails

Der User-Service verwendet im Profil `prd` SES SMTP in Frankfurt über Port 587
mit verpflichtendem STARTTLS und SMTP AUTH. Systemnachrichten kommen von
`noreply@gratilo.eu`, allgemeine Kommunikation von `info@gratilo.eu`. Lokal bleibt
MailHog aktiv. Die [SES-Anleitung](docs/amazon-ses.md) beschreibt die manuellen
Schritte für SMTP-Credentials, Secrets Manager, IAM, ECS und DNS. Eine
[ECS-Konfigurationsvorlage](deployment/ecs/user-service-ses.container.json)
enthält ausschließlich nicht geheime Werte und Platzhalter für Secret-ARNs.

## Feature-Toggle: Trinkgeld-Pools

Die Gruppenaufteilung bleibt vollständig deaktiviert. Beide Backends verweigern
bei `FEATURE_TIP_POOLS_ENABLED=true` den Start. Poolverwaltung, Mitgliedschaften
und Einladungen bleiben ausgeblendet; öffentliche und interne Pool-APIs sind nicht
registriert. Der Checkout enthält keine Verteilungslogik mehr. Bestehende Pooldaten
und historische Buchungen bleiben erhalten. Die Bereinigung von Mitgliedschaften
beim Löschen eines Kontos bleibt aktiv.

## Stripe Connect: Stand und manuelle Einrichtung

Einzeltrinkgelder werden über Stripe-hosted Checkout als Destination Charge an
genau ein Connected Account verarbeitet. Vor jedem Checkout prüft der Server die
aktive Zahlungsseite und ein frisch geladenes Accounts-v2-Konto mit passender ID,
`livemode=false`, nicht geschlossenem Konto und angewendeten Konfigurationen
`merchant`/`recipient`. Diese v2-Capabilities müssen jeweils `status=active` haben:
`configuration.merchant.capabilities.card_payments`,
`configuration.recipient.capabilities.stripe_balance.payouts` und
`configuration.recipient.capabilities.stripe_balance.stripe_transfers`.
Die explizite v2-Abfrage enthält `configuration.merchant`, `configuration.recipient`
und `requirements` im `include`. Fehlende/ungültige Antworten, offene `currently_due`-
oder `past_due`-Pflichtangaben sowie laufende Stripe-Prüfungen blockieren Trinkgelder.
Zukünftige `eventually_due`-Anforderungen allein sperren ein sonst aktives Konto nicht.
Die bisherigen DTO-Felder `chargesEnabled`/`payoutsEnabled`/`detailsSubmitted` werden
nur als kompatible Projektion dieser Gesamtentscheidung ausgegeben; sie sind keine
ungeprüft übernommenen v1-Felder und keine clientseitige Autorisierung.
Identitäts- und Bankdaten werden im eingebetteten Stripe-Onboarding erfasst.
Accounts-v2-Aufrufe verwenden die feste Version `2026-09-30.endive`.

Checkout-Wiederholungen verwenden dieselbe Transaktion und einen davon abgeleiteten
Stripe-Idempotenzschlüssel; geänderte Beträge/Nachrichten/Gebühren dürfen denselben
Anfrageschlüssel nicht wiederverwenden. Ein Trinkgeld kann zwischen 1 und 10.000 EUR
liegen. Bei übernommener Servicegebühr wird nur der Trinkgeldbetrag an den Mitarbeiter
übertragen; der Aufschlag verbleibt bei der Plattform. Dies ist eine konfigurierte
Servicegebühr, keine Zusage über Stripes tatsächliche Kosten. Stripe berechnet seine
Gebühren der Plattform. Ohne Aufschlag trägt die Plattform diese Kosten.

Buchungen erfolgen erst nach bestätigtem `paid`-Status oder signiertem
`payment_intent.succeeded`. Doppelte/verspätete Fehler überschreiben keine gebuchten
Trinkgelder; vollständige Rückerstattungen bleiben endgültig. Demo-Onboarding,
Demo-Trinkgelder und manuelle Ledger-/Refund-/Cancel-HTTP-Schreibwege sind gesperrt.
Auszahlungen über Gratilo bleiben ausdrücklich simuliert und lösen keinen Banktransfer
aus. Für echte Bankauszahlungen ist eine gesonderte Freigabe und Implementierungsprüfung
nötig. Teilrückerstattungen und Disputes werden noch nicht im Ledger abgebildet;
Erstattungen im Stripe-Dashboard müssen bei Destination Charges auch den Transfer
berücksichtigen. Kein Refund wird durch Gratilo ausgelöst.

Manuell in Stripe für eine Sandbox bzw. ein Testkonto einrichten:

1. Connect und Accounts v2 verfügbar machen; Plattformprofil, Verantwortlichkeiten,
   DE/EUR und Express-Onboarding prüfen. Schlüssel müssen aus derselben Sandbox stammen.
2. `sk_test_…` ausschließlich im Backend-Secret hinterlegen. Beim ausdrücklich
   aktivierten Test-Build kommt `pk_test_…` aus der GitHub-Variable
   `STRIPE_TEST_PUBLISHABLE_KEY`; Live-Schlüssel werden abgewiesen.
3. Einen Test-Webhook für Plattformereignisse auf
   `https://<TEST-HOST>/payment-service/webhooks/stripe` anlegen. Lokal kann Stripe CLI
   auf Port 8082 weiterleiten; dessen Signatur-Secret ist ein eigenes `whsec_…`.
4. Ereignisse: `checkout.session.completed`, `checkout.session.expired`,
   `checkout.session.async_payment_succeeded`, `checkout.session.async_payment_failed`,
   `payment_intent.succeeded`, `payment_intent.payment_failed`,
   `payment_intent.canceled`, `charge.refunded`. Das Webhook-Secret im Backend setzen.
5. Einen Mitarbeiter per Stripe-Test-Onboarding einrichten und den Status in Gratilo
   synchronisieren. Danach mit Stripe-Testkarte Erfolg, Ablehnung, erneute Zustellung
   eines Webhooks und vollständige Rückerstattung prüfen. Dieser externe Testlauf
   wurde hier nicht ausgeführt. Apple Pay/Google Pay separat auf unterstützten Geräten
   und der Testdomain prüfen.

Manuell in AWS **erst nach Freigabe**, bevorzugt in einer getrennten Testumgebung:

- Secrets Manager: Stripe-Testschlüssel und Test-Webhook-Secret hinterlegen; in der
  ECS-Task als `STRIPE_SECRET_KEY`/`STRIPE_WEBHOOK_SECRET` injizieren. Execution Role
  auf die konkreten Secret-ARNs begrenzen; bei eigenem KMS-Key passendes Decrypt-Recht.
- Frontend mit `VITE_PAYMENTS_ENABLED=true` bauen; ein Stripe-Publishable-Key ist
  wegen des Stripe-hosted Onboardings und Stripe Checkout nicht für die Laufzeit
  erforderlich. Der freigegebene Actions-Build erhält den vorhandenen
  `STRIPE_TEST_PUBLISHABLE_KEY` ausschließlich als `VITE_STRIPE_PUBLISHABLE_KEY`.
- Für einen Test-Rollout im manuellen Workflow `Gratilo Release & Deploy Production`
  `stripe_test_mode=true` und zusätzlich `ENABLE STRIPE TEST PAYMENTS` angeben;
  die bestehende Release-Bestätigung und Production-Umgebungsfreigabe gelten weiter.
  Ohne dieses Opt-in bleiben Zahlungen deaktiviert.
- Payment-Service: `PAYMENTS_ENABLED=true`, `STRIPE_TEST_ONLY=true`,
  `STRIPE_SANDBOX_PAYOUTS=true`, `STRIPE_DEMO_ENABLED=false`; beide Backends:
  `FEATURE_TIP_POOLS_ENABLED=false`. `STRIPE_SANDBOX_PAYOUTS=true` schreibt nur
  simulierte Buchungen ins Gratilo-Test-Ledger und ruft nie Stripes `/v1/payouts`
  auf. Ohne den sicheren Sandbox-Modus verweigert die Test-Deployment-Prüfung den
  Rollout.
- HTTPS-Testdomain/ALB/Nginx zum Frontend und zum Webhook routen; Backend-Ports privat
  lassen. Egress auf `api.stripe.com:443`, DB-Zugriff, Healthchecks und interne
  Erreichbarkeit des User-Service (`USER_SERVICE_URL`) prüfen.
- `FRONTEND_BASE_URL`, `STRIPE_SUCCESS_URL` mit `{CHECKOUT_SESSION_ID}` und
  `STRIPE_CANCEL_URL` auf dieselbe Testdomain setzen. Keine Secrets in Logs/Images.

Die vorbereitete AWS-Testeinrichtung verwendet den tatsächlichen lokalen Code und
leitet die Execution Role aus der aktuell laufenden ECS-Task ab. Ein rein lesendes
Prüfwerkzeug erstellt lokale Review-Dateien, ohne AWS-Schreiboperationen auszuführen:

```shell
aws sso login --profile gratilo
python3 deployment/scripts/prepare_stripe_test.py --check-stripe
```

Es prüft die AWS-Identität gegen Konto `070351676632` und verwendet ausschließlich
`eu-central-1`. `SecretString` wird nur im Prozessspeicher ausgewertet; ausgegeben
werden keine Secret-Werte. Die Prüfung verlangt die JSON-Felder `secret_key` mit
`sk_test_` und `webhook_secret` mit `whsec_` in `gratilo/prd/stripe-test`. Die
abgeleitete Policy erlaubt nur `secretsmanager:GetSecretValue` auf den vollständigen
ermittelten Secret-ARN. Bei einem kundeneigenen KMS-Key enthält sie zusätzlich ein
auf diesen Key, Secrets Manager und dessen Secret-Verschlüsselungskontext begrenztes
`kms:Decrypt`. Die Key Policy sowie Grenzen/SCPs sind zusätzlich zu berücksichtigen.
Eine bereits erlaubte Berechtigung erfordert keine zusätzliche Allow-Policy.

Die Review-Artefakte liegen mit Dateimodus 0600 unter `.local/stripe-test-plan/`
(ignoriert von Git): `audit.json`, `execution-policy.proposed.json` und, sobald
der private Freigabecode im Secret vorhanden ist, `task-definition.proposed.json`.
Die Task enthält die exakten JSON-Key-Referenzen `<secret-arn>:secret_key::`,
`<secret-arn>:webhook_secret::` und `<secret-arn>:activation_code::` nur beim
Payment-Service. Letztere wird als `PAYMENTS_TEST_ACTIVATION_CODE` injiziert. Ihre Images stammen zunächst
unverändert aus der laufenden Task. **Vor einer Registrierung müssen alle drei
Images durch geprüfte Digests des neuen Builds ersetzt und der finale Diff freigegeben
werden.** Das Prüfwerkzeug registriert nichts und aktualisiert keinen Service.

Im Profil `prd` verlangt `PAYMENTS_ENABLED=true` entweder einen privaten
`PAYMENTS_TEST_ACTIVATION_CODE` oder die bisherige UUID-Liste. Empfohlen ist der
Freigabecode: 32 bis 256 zufällige Zeichen, nur im Backend, im bestehenden Secret
`gratilo/prd/stripe-test` unter dem zusätzlichen JSON-Feld `activation_code`.
Weder der Freigabecode noch Stripe-Secrets gehören in GitHub-Buildvariablen,
Frontend-Dateien, Logs oder QR-Links. Der Code wird bei der Profileingabe verdeckt
angezeigt, nach dem Absenden gelöscht und nicht im Browser gespeichert.

Der vollständige Selbsttest benötigt keine manuelle UUID-Abfrage:

1. Normal registrieren, E-Mail bestätigen und anmelden.
2. Im Profil den privaten Test-Freigabecode eingeben und „Mein Konto für den Test
   freigeben“ wählen. Der Server nimmt die Benutzerkennung ausschließlich aus dem
   gültigen JWT; im Request wird kein Zielkonto akzeptiert. Eine neue, noch leere
   Connected-Account-Zeile wird bei Bedarf angelegt.
3. Das Stripe-Test-Onboarding im Profil starten und abschließen. Der Freigabecode
   allein erlaubt noch keine Trinkgeldannahme. Der offene Profilbereich prüft den
   Stripe-Status während des Onboardings alle 5 Sekunden, sonst alle 30 Sekunden
   sowie bei erneutem Fensterfokus. Erst nach der positiven Accounts-v2-Prüfung
   wird der eigene QR-Code angezeigt und der Zahlungslink kopierbar.
4. Den unveränderten QR-Link im privaten Browserfenster öffnen. Gäste benötigen
   weder Gratilo-Login, Freigabecode noch Stripe-Registrierung. Die serverseitige
   Testzugangssperre prüft den freigegebenen Empfänger; die separate Prüfung des
   tatsächlichen Stripe-Status bleibt vor jedem Checkout verbindlich.
5. Mit einer Stripe-Testkarte zahlen. Die Transaktion wird über serverseitige
   Empfängerbindung und signierte Stripe-Metadaten dem eigenen Konto zugeordnet.
   Die Transaktionsliste aktualisiert sich alle 15 Sekunden und beim Fensterfokus.
6. Bei Bedarf im Profil „Testfreigabe widerrufen“ wählen. Danach können keine neuen
   Gast-Checkouts an dieses Konto erzeugt werden. Historische Buchungen bleiben
   erhalten. Bereits erzeugte Stripe-Checkout-URLs werden nicht automatisch gelöscht.

Die Freigabe wird mit Migration `005_test_access.sql` als
`payment_mgmt.connected_accounts.test_access_enabled` gespeichert, standardmäßig
`false`. Die App verwendet UUIDs weiter intern als stabile Kontokennungen; niemand
muss sie für diesen Ablauf aus der Datenbank lesen oder in eine Liste eintragen.
Die optionale `PAYMENTS_TEST_USER_IDS`-Liste bleibt nur für bestehende Konfigurationen
kompatibel; darin aufgeführte Freigaben müssen auf Konfigurationsebene widerrufen
werden. Eine Rotation des Freigabecodes entzieht bereits erteilte Freigaben nicht.
Der private Code darf nur autorisierten Testern bekannt sein; wer ihn kennt, kann
seinen eigenen angemeldeten Account freigeben. Öffentliche Registrierung allein
verleiht keine Testrechte.

Onboarding, Synchronisation und simulierte Auszahlungen bleiben authentifizierten,
freigegebenen Kontoinhabern vorbehalten. Die anonyme Bestätigung akzeptiert nur einen
bei Stripe als bezahlt gelesenen Test-Session-Identifier, dessen ID und Metadaten zur
lokalen Transaktion passen; sie erstellt keine neue Zahlung. Der Webhook prüft weiter
seine eigene Signatur. Testdaten liegen bei Nutzung derselben ECS-Task weiterhin in
der gemeinsamen Produktionsdatenbank. Der Code ist lokal vorbereitet; Secret-Ergänzung,
Migration und Rollout auf AWS werden erst nach ausdrücklicher Freigabe ausgeführt.

Der neue manuelle Workflow `.github/workflows/stripe-test-build.yml` baut und scannt
nur Images und lädt Artefakte hoch; er kann weder ECR veröffentlichen noch Tags
anlegen oder ECS deployen. Er verlangt `main` und die Bestätigung `BUILD STRIPE TEST`.
`VITE_PAYMENTS_ENABLED=true` gilt ausschließlich für diesen ausdrücklich gewählten
Testbuild. Es werden keine Stripe-Schlüssel an GitHub oder Docker übergeben. Normale
Release-/PR-Builds behalten deaktivierte Zahlungen. Der bisherige Produktionsrenderer
verweigert aktivierte Zahlungen weiterhin absichtlich; den Release-Workflow nicht für
diese Testaktivierung verwenden. Neue lokale Dateien müssen erst nach Review in den
gewählten Commit gelangen, bevor GitHub sie bauen kann.

Die öffentliche QR-Seite fragt `GET /payment-service/tips/{userId}/eligibility`
beim Öffnen, alle 30 Sekunden und bei erneutem Fensterfokus ab. Bis zu einer
positiven Antwort bleibt der Bezahlbutton gesperrt; bei Fehlern oder entzogener
Freigabe wird er wieder deaktiviert. Die Antwort enthält nur `canReceiveTips` und
`Cache-Control: no-store`, keine Stripe-ID oder KYC-Daten. Dieselbe Entscheidung
wird bei Onboarding-Sync und nochmals vor jeder Checkout-Erstellung verwendet.
Ein zuvor positives Browserergebnis ersetzt diese Backend-Prüfung niemals.
Es besteht kein zusätzlicher v2-Webhook und kein Hintergrund-Scheduler; der
gespeicherte Status wird bei diesen frischen Abfragen aktualisiert. Zwischen
Abfragen kann die Darstellung bis zu 30 Sekunden alt sein, die Checkout-Prüfung
bleibt davon unabhängig. Bereits bei Stripe erzeugte Sessions werden durch diese
Änderung nicht nachträglich storniert; ihre spätere Verarbeitung unterliegt Stripe.

Ein einzelner Snapshot-Testwebhook für **Plattformereignisse** genügt für die oben
aufgeführten Checkout-/PaymentIntent-/Refund-Ereignisse. Keine zusätzlichen
Connect-Account-/Payout- oder v2-Thin-Ereignisse in dieselbe Einrichtung aufnehmen.
Die Statusabfrage erfolgt nativ über Accounts v2; Account Sessions verwenden
weiterhin den dafür vorgesehenen v1-Endpunkt. Thin Events würden einen eigenen Verarbeitungsweg und Signing-Secret
benötigen und werden derzeit mit 400 abgewiesen. Mit `GET /v1/webhook_endpoints`
kann das Prüfwerkzeug URL, Testmodus und Ereignisliste prüfen, jedoch nicht beweisen,
dass das gespeicherte Signing-Secret zum Endpoint gehört. Das muss nach genehmigtem
Deployment anhand einer echten signierten Stripe-Testzustellung bestätigt werden.

Vor **jeder AWS-Schreiboperation** werden der konkrete IAM-/KMS-/Task-Diff und dessen
Zielressourcen zur Freigabe vorgelegt. Ein ECS-Service-Update, Neustart oder eine
öffentliche Aktivierung benötigt eine gesonderte Deploymentfreigabe. Keine neue
NAT-, ALB-, ECS- oder Secret-Ressource ist automatisch vorgesehen. IAM selbst verursacht
keine zusätzliche Servicegebühr; Builds/ECR-Speicherung, Secret-Zugriffe, Logs und
eventuelle zusätzliche Task-Laufzeit können bestehende Nutzungskosten erhöhen.
Netzwerkrouten und Security Groups belegen noch keine funktionierende TLS-Verbindung
aus der Task; NAT/NACL/DNS und Stripe-Erreichbarkeit müssen vor Aktivierung bestätigt
werden. Die gemeinsamen Produktionsdaten und ein Task-Rollout sind die wesentlichen
Betriebsrisiken; ein Image-Rollback entfernt keine Stripe-Testdaten.

Keine AWS-Ressourcen, Secrets oder Deployments wurden hierfür verändert.

Quellen: [Destination Charges](https://docs.stripe.com/connect/destination-charges),
[Accounts v2](https://docs.stripe.com/connect/accounts-v2),
[ECS JSON-Key-Secrets](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/secrets-envvar-secrets-manager.html),
[IAM-Kosten](https://docs.aws.amazon.com/IAM/latest/UserGuide/introduction.html),
[Checkout-Status](https://docs.stripe.com/api/checkout/sessions/object).

## User-Service API

The user service listens on `http://localhost:8081` and exposes:

- `POST /user-service/users/register` – registration and verification email
- `POST /user-service/auth/login` – JWT login (after email verification)
- `GET /user-service/auth/verify?token=...` – verify email address
- `POST /user-service/auth/password-reset/request` – request reset email
- `POST /user-service/auth/password-reset/confirm` – set a new password
- `GET /user-service/users/me` – show the authenticated profile
- `PUT /user-service/users/profile` – edit profile or change password
- `POST /user-service/internal/notifications/email` – send a system email using
  the `X-Internal-Secret` header
- `POST /user-service/internal/notifications/email/general` – send general
  communication from the configured info address using the same internal header

PostgreSQL is shared by the services (`gratilodb`), but data and Liquibase
tables are isolated in the `user_mgmt` and `payment_mgmt` schemas.

## Manueller AWS-ECS-Betrieb

Die vollständige Konfiguration, Secrets, Stripe-Sandbox und manuellen AWS-Schritte
stehen in [docs/manual-ecs-production.md](docs/manual-ecs-production.md).
ECS-Deployment und Infrastrukturänderungen erfolgen ausschließlich in der AWS-Konsole.
GitHub kann nach ausdrücklicher Freigabe geprüfte Sandbox-Images nach ECR veröffentlichen;
der ECS-Deploy-Job bleibt deaktiviert.

## Umbenennung in Gratilo

Java-Pakete verwenden `com.gratilo`, Gradle den Projektnamen `gratilo` und npm
`@gratilo/frontend`. Das GitHub-Repository ist
[`stefanbeike/gratilo`](https://github.com/stefanbeike/gratilo).
Die Startskripte heißen `start_gratilo.sh` und `stop_gratilo.sh`.

Docker Compose verwendet das Projekt `gratilo`, das Netzwerk `gratilo-network`,
die Datenbank `gratilodb` und den MinIO-Bucket `gratilo`. Beim Wechsel von einer
bestehenden lokalen Installation zuerst den alten Stack mit dessen bisheriger
Compose-Konfiguration stoppen. Daten bei Bedarf vorher per `pg_dump` bzw.
MinIO-Export sichern und in die neue Installation importieren. Die neuen
Compose-Projektnamen erzeugen eigene Volumes; alte Volumes werden nicht gelöscht
oder automatisch übernommen. Profilbilder werden aktuell als JPEG-Data-URLs in PostgreSQL gespeichert;
MinIO wird von den drei Anwendungen nicht verwendet.

Historische Liquibase-Changesets behalten den Autor `tipster`, weil Autor, ID
und Dateipfad gemeinsam die Identität einer bereits ausgeführten Migration
bilden. Neue Changesets verwenden `gratilo`. Die Git-Historie bleibt erhalten.
Der lokale Checkout-Ordner kann nach dem Schließen der IDE in `gratilo`
umbenannt und anschließend erneut geöffnet werden.

Der [manuelle Gesamtworkflow Gratilo Release & Deploy Production](docs/cicd.md)
versioniert, baut und prüft alle drei Images und deployt erst nach unabhängiger
Production-Freigabe. Pushes und Tags deployen nicht; Zahlungen bleiben deaktiviert.
