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
`STRIPE_SECRET_KEY` wird nur an den Payment-Service gegeben; der
`STRIPE_PUBLISHABLE_KEY` wird beim Frontend-Build eingebettet. Ein
`STRIPE_WEBHOOK_SECRET` ist bei aktivierten Zahlungen für die Signaturprüfung erforderlich.
Zahlungen bleiben zunächst ausgeschaltet. Für einen lokalen Testlauf explizit
`PAYMENTS_ENABLED=true` und `VITE_PAYMENTS_ENABLED=true` setzen; Testschlüssel
`sk_test_…`, `pk_test_…` und das passende `whsec_…` verwenden.
`STRIPE_TEST_ONLY=true`, `STRIPE_SANDBOX_PAYOUTS=true` und
`FEATURE_TIP_POOLS_ENABLED=false` müssen erhalten bleiben. Die Anwendung bricht
bei Live-Schlüsseln oder aktivierten Pools den Start ab.
Vite direkt benötigt `VITE_STRIPE_PUBLISHABLE_KEY` in der Frontend-Umgebung;
Compose übersetzt dafür `STRIPE_PUBLISHABLE_KEY` aus der Root-`.env`.

Danach den Stack mit `--build` neu bauen, damit der Publishable Key in die
Frontend-Dateien gelangt:

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
aktive Zahlungsseite, Stripe-Testmodus, Charges, Payouts und Transfers-Capability.
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
2. `sk_test_…` ausschließlich im Backend, `pk_test_…` nur im Frontend-Build hinterlegen.
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
- Frontend mit `VITE_PAYMENTS_ENABLED=true` und `VITE_STRIPE_PUBLISHABLE_KEY=pk_test_…`
  bauen; dies sind Buildwerte. Die bestehenden Release-Workflows bleiben auf `false`.
- Payment-Service: `PAYMENTS_ENABLED=true`, `STRIPE_TEST_ONLY=true`,
  `STRIPE_SANDBOX_PAYOUTS=true`, `STRIPE_DEMO_ENABLED=false`; beide Backends:
  `FEATURE_TIP_POOLS_ENABLED=false`. Die bisherige Task-Vorlage bleibt deaktiviert
  und muss für einen genehmigten Testbetrieb ausdrücklich angepasst werden.
- HTTPS-Testdomain/ALB/Nginx zum Frontend und zum Webhook routen; Backend-Ports privat
  lassen. Egress auf `api.stripe.com:443`, DB-Zugriff, Healthchecks und interne
  Erreichbarkeit des User-Service (`USER_SERVICE_URL`) prüfen.
- `FRONTEND_BASE_URL`, `STRIPE_SUCCESS_URL` mit `{CHECKOUT_SESSION_ID}` und
  `STRIPE_CANCEL_URL` auf dieselbe Testdomain setzen. Keine Secrets in Logs/Images.

Keine AWS-Ressourcen, Secrets oder Deployments wurden hierfür verändert.

Quellen: [Destination Charges](https://docs.stripe.com/connect/destination-charges),
[Accounts v2](https://docs.stripe.com/api/v2/core/accounts/create),
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
