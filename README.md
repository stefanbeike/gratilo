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
`STRIPE_WEBHOOK_SECRET` wird später für die Signaturprüfung des Webhook-Endpunkts
benötigt.

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

Die Gruppenaufteilung ist standardmäßig deaktiviert. Setze
`FEATURE_TIP_POOLS_ENABLED=true` in **beiden** Backend-Services, um sie wieder
zu aktivieren. In Docker Compose genügt derselbe Eintrag in der lokalen `.env`;
anschließend die Services neu erstellen. Bei einem IDE-Start die Variable in
beiden Run-Konfigurationen setzen. Ohne Variable gilt `false`.

Der User-Service veröffentlicht den Status über `GET /user-service/features`.
Das Frontend liest ihn beim Laden; ein neuer Frontend-Build ist für das
Umschalten nicht erforderlich. Nach einer Konfigurationsänderung beide Backends
neu starten und die Browserseite neu laden. Wenn die Statusabfrage fehlschlägt,
bleibt die Funktion im Frontend deaktiviert.

Bei deaktiviertem Toggle werden Poolverwaltung, Mitgliedschaften und
Einladungsaktionen ausgeblendet. Die Pool-APIs und die interne Verteilungs-API
sind nicht registriert. Öffentliche Zahlungsprofile zeigen keinen Poolnamen,
und der Payment-Service führt keine Pool-Aufteilung aus; Trinkgelder werden
wie Einzelzahlungen für den Empfänger verarbeitet. Bestehende Pooldaten und
historische Buchungen bleiben erhalten und sind nach Reaktivierung wieder
nutzbar. Die Bereinigung von Mitgliedschaften beim Löschen eines Kontos bleibt
aktiv, damit keine verwaisten Mitgliedschaften entstehen.

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

## Production: gratilo.eu

Die [Deployment-Anleitung](docs/aws-ecs-deployment.md) beschreibt den gemeinsamen
ECS-Fargate-Task mit Frontend und beiden Backends, OIDC, die manuellen AWS- und
GitHub-Schritte sowie Erstdeployment und Rollback. Die einzige produktive Pipeline
ist der manuell ausgelöste Workflow `release.yml` auf `main`. Er verlangt grüne CI,
den expliziten Opt-in-Schalter und Freigaben im Environment `production`.

Die Produktionsdatenbank in RDS heißt weiterhin `tipsterdb`; die lokale
Compose-Datenbank heißt `gratilodb`. Die Produktion verwendet `https://gratilo.eu`
für Website, E-Mail-Links und Stripe-Redirects. Ein neuer Liquibase-Changeset migriert
alte `.com`-/`.de`-Zahlungslinks, ohne historische Changesets umzuschreiben.

AWS-Infrastruktur wird manuell angelegt. Es gibt keinen automatisch ausführbaren
Terraform-Workflow und keine Infrastrukturänderungen durch die Deployment-Pipeline.

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
oder automatisch übernommen. Gespeicherte Profilbild-URLs beim Import an den
neuen Bucket anpassen.

Historische Liquibase-Changesets behalten den Autor `tipster`, weil Autor, ID
und Dateipfad gemeinsam die Identität einer bereits ausgeführten Migration
bilden. Neue Changesets verwenden `gratilo`. Die Git-Historie bleibt erhalten.
Der lokale Checkout-Ordner kann nach dem Schließen der IDE in `gratilo`
umbenannt und anschließend erneut geöffnet werden.
