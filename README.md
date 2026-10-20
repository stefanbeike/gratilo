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

### Lokaler Stripe-Sandbox-Stack (Windows 11 / WSL2)

Dieser eigenständige Stack verwendet `docker-compose/compose.stripe-test.yml`,
Projekt `gratilo-stripe-test`, ein eigenes Netzwerk und das Volume
`gratilo-stripe-test_stripe-postgres`. Er nutzt keine AWS-Secrets oder RDS und
keine vorhandene Gratilo-Datenbank. Produktionsdateien und produktive Redirects
bleiben unverändert. Befehle im Repository-Stamm in **WSL Bash** ausführen;
Docker Desktop muss für diese WSL-Distribution erreichbar sein. Den Browser
unter Windows öffnen. Java und Node auf dem Host sind zum Compose-Start nicht nötig.

Gefundene Integrationslücken vor dieser Erweiterung:

- Der normale Compose-Stack deaktiviert Zahlungen und Frontend-Checkout standardmäßig.
  Der Publishable Key fehlte in den Compose-Buildargumenten; ein Aktivierungscode
  wurde dem Payment-Service nicht übergeben.
- In der vorhandenen lokalen Root-`.env` fehlten die für den Service-Start
  erforderlichen JWT-/Internal-API-Schlüssel. Der neue Initialisierer erzeugt
  dafür unabhängige lokale Werte, ohne die bestehende Datei zu überschreiben.
- Nur der User-Service bekam `FRONTEND_BASE_URL`; Connect-Onboarding im Payment-Service
  konnte dadurch zu einem anderen Host zurückleiten als Checkout und Registrierung.
- Der optionale Listener verwendete den Secret Key als Kommandozeilenargument,
  keinen expliziten Connect-Forwarder und keine gemeinsame Einrichtung des Listener-
  Signatursecrets mit dem Payment-Service.
  Das hier vorhandene aktuelle CLI-Image verlangt außerdem eine ausdrückliche
  Ereignisauswahl; beide Listener-Konfigurationen enthalten jetzt `--events`.
- Vite hatte eine feste WSL-IP für den Payment-Proxy. Der Default ist jetzt localhost;
  der bestehende `PAYMENT_SERVICE_PROXY_TARGET` bleibt für IDE-Mischbetrieb nutzbar.
- Die Backend-Dockerfiles laden standardmäßig das öffentliche RDS-CA-Bundle.
  Der lokale Stripe-Stack überspringt das über `INCLUDE_RDS_CA=false`; der
  Produktionsdefault bleibt `true`.
- Die frühere Anleitung behauptete einen generellen Startabbruch bei aktivierten
  Pools. Pooling ist inzwischen implementiert und kann lokal ausdrücklich aktiviert werden.
- Die Services verwenden **Micronaut**, nicht Spring Boot. Die `prd`-Profile
  enthalten bewusst `https://gratilo.eu`; der neue Stack aktiviert nur `docker`.

**Einrichtung und Start:**

```bash
python3 docker-compose/stripe_test.py init
# .env.stripe-test lokal prüfen/bearbeiten, z.B. mit nano .env.stripe-test
python3 docker-compose/stripe_test.py check-stripe
python3 docker-compose/stripe_test.py webhook-secret
python3 docker-compose/stripe_test.py check
python3 docker-compose/stripe_test.py up
```

Falls Docker Paketdownloads mit TLS-Handshake-Abbrüchen nicht abschließen kann,
ist ein optionaler **WSL-Hostbuild** vorhanden. Dafür JDK 21 in WSL installieren
und bei Bedarf `JAVA_HOME` auf dessen Installationsverzeichnis setzen:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  python3 docker-compose/stripe_test.py up --host-build
```

Dieser Weg verwendet zusätzlich `compose.stripe-test.host-build.yml`, baut
die beiden `installDist`-Artefakte mit Gradle in WSL und kopiert ausschließlich
deren Runtime-Libraries in `Dockerfile.local-runtime`. Compose mit Unterstützung
für `additional_contexts` (ab 2.17) ist erforderlich. Netzwerk, PostgreSQL,
Secrets und Sicherheitsprüfungen sind identisch. Es wird kein TLS-Check umgangen.
Nach Änderungen für diesen Weg wieder denselben `--host-build`-Befehl verwenden.

`init` erstellt die von Git ignorierte `.env.stripe-test`. Bereits vorhandene
`sk_test_`- und `pk_test_`-Schlüssel aus der ignorierten Root-`.env` können übernommen
werden. Datenbankpasswort, JWT-Schlüssel, interner API-Schlüssel und Aktivierungscode
werden unabhängig zufällig erzeugt. Ein vorhandenes Webhook-Secret wird ausdrücklich
**nicht** übernommen. Eine existierende `.env.stripe-test` wird nicht überschrieben.
Auf Windows schützt zusätzlich die ACL des Benutzerverzeichnisses die Datei;
WSL-Dateimodi auf `/mnt/c` hängen von den DrvFS-Mountoptionen ab.

| Variable in `.env.stripe-test` | Bedeutung |
| --- | --- |
| `STRIPE_SECRET_KEY` | privater API-Key der dedizierten Stripe-Sandbox; ausschließlich Testschlüssel |
| `VITE_STRIPE_PUBLISHABLE_KEY` | öffentlicher Testkey derselben Sandbox; wird als Frontend-Buildargument übergeben |
| `STRIPE_WEBHOOK_SECRET` | vom **lokalen Listener** ermitteltes Signatursecret |
| `LOCAL_POSTGRES_PASSWORD` | separat generiertes lokales PostgreSQL-Passwort |
| `JWT_SECRET` | separat generierter JWT-Schlüssel |
| `INTERNAL_API_KEY` | separat generierter Schlüssel zwischen den lokalen Services |
| `PAYMENTS_TEST_ACTIVATION_CODE` | privater lokaler Aktivierungscode; niemals `VITE_` oder in QR-/Zahlungslinks |
| `FRONTEND_BASE_URL` | standardmäßig `http://localhost:3001`; optional eigene HTTPS-Entwicklungs-Tunnel-URL |
| `FEATURE_TIP_POOLS_ENABLED` | standardmäßig `false`; für Team-/Pooltests ausdrücklich `true` setzen und `up` erneut ausführen |

Der Stack setzt `PAYMENTS_ENABLED=true`, `STRIPE_TEST_ONLY=true`,
`STRIPE_SANDBOX_PAYOUTS=true`, `STRIPE_DEMO_ENABLED=false` und beim Frontend-Build
`VITE_PAYMENTS_ENABLED=true` fest. Live-Keys und fehlende Signatursecrets werden
abgelehnt. Das Frontend verwendet den bereits implementierten Stripe-hosted Checkout
und Stripe-hosted Onboarding; der Publishable Key wird dafür derzeit nicht zur
Laufzeit benötigt. Sein Testpräfix wird trotzdem geprüft und er wird wie angefordert
beim lokalen Build bereitgestellt. Die Zuordnung beider Keys zur selben Sandbox
im Stripe-Dashboard prüfen. `check-stripe` bestätigt nur den Secret Key mit einer
lesenden Balance-Abfrage und `livemode=false`; es führt keine Zahlung aus.

**URLs und lokale Daten:**

- Frontend und API-Proxy: `http://localhost:3001`.
- E-Mail-Bestätigung / Passwortreset: lokale Testmails unter `http://localhost:18025`.
- Checkout-Erfolg: `http://localhost:3001/pay/success?session_id={CHECKOUT_SESSION_ID}`.
- Checkout-Abbruch: `http://localhost:3001/pay/cancelled?session_id={CHECKOUT_SESSION_ID}`.
- Connect-Rückkehr: `http://localhost:3001/account?stripe_onboarding=return`,
  Refresh: dieselbe Route mit `stripe_onboarding=refresh`.
- PostgreSQL und beide Backends sind nur im eigenen Docker-Netz erreichbar.
  Die Datenbank besitzt die vorhandenen Schemas `user_mgmt` und `payment_mgmt`;
  Liquibase führt beide bestehenden Changelogs aus. Keine RDS-/AWS-Zugangsdaten mounten.
- MinIO ist für diesen Ablauf nicht erforderlich: Profilbilder werden derzeit als
  Data-URLs gespeichert. Der bestehende Standardstack wird nicht gestoppt oder gelöscht.

**Stripe CLI und Webhooks:**

Die [Stripe CLI](https://docs.stripe.com/stripe-cli) in WSL installieren.
Optional `stripe login` ausführen und im Browser die gewünschte Sandbox auswählen.
Die automatisierte Einrichtung verwendet danach ausdrücklich den Testkey aus
`.env.stripe-test` über `STRIPE_API_KEY`, nicht irgendein angemeldetes Live-Profil.
`webhook-secret` ruft `stripe listen --print-secret` mit diesem Key auf und schreibt
das Ergebnis verdeckt in die lokale Datei. `up` startet einen Docker-Listener mit
demselben Key und Gerätenamen; die Signaturprüfung im Payment-Service bleibt aktiv.

Der Listener führt im eigenen Docker-Netz aus:

```bash
stripe listen \
  --events checkout.session.completed,checkout.session.expired,checkout.session.async_payment_succeeded,checkout.session.async_payment_failed,payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled,charge.refunded,charge.dispute.created,charge.dispute.closed,payout.paid,payout.failed,payout.canceled,account.updated \
  --forward-to http://payment-service:8082/payment-service/webhooks/stripe \
  --forward-connect-to http://payment-service:8082/payment-service/webhooks/stripe
```

Eine gleichwertige manuelle Variante über den Frontend-Proxy in WSL ist:

```bash
stripe login
stripe listen \
  --events checkout.session.completed,checkout.session.expired,checkout.session.async_payment_succeeded,checkout.session.async_payment_failed,payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled,charge.refunded,charge.dispute.created,charge.dispute.closed,payout.paid,payout.failed,payout.canceled,account.updated \
  --forward-to http://localhost:3001/payment-service/webhooks/stripe \
  --forward-connect-to http://localhost:3001/payment-service/webhooks/stripe
```

Bei dieser Variante den automatisch gestarteten Containerlistener zuerst stoppen,
das vom manuellen Listener ausgegebene Secret in `.env.stripe-test` eintragen und
den Payment-Service ohne erneutes Starten des Containerlisteners neu erzeugen:

```bash
docker compose --env-file .env.stripe-test -f docker-compose/compose.stripe-test.yml --profile webhooks stop stripe-cli
# STRIPE_WEBHOOK_SECRET in .env.stripe-test lokal aktualisieren
docker compose --env-file .env.stripe-test -f docker-compose/compose.stripe-test.yml up -d payment-service
```

Nie ein produktives Endpoint-Secret dafür verwenden. Ein Listener liefert für
Plattform- und Connect-**Snapshot**-Events dasselbe lokale Signatursecret und
kann beide an denselben Handler senden. Destination Charges und Separate Charges
and Transfers erzeugen ihre Zahlungsevents auf der Plattform; Connect-Weiterleitung
deckt zusätzliche Ereignisse verbundener Konten ab. Der vorhandene Handler verarbeitet
Zahlungsstatus, Erstattungen und Disputes, aber keine Accounts-v2-**Thin**-Events;
diese werden abgelehnt. Accountstatus wird über die vorhandene API-Synchronisation
aktualisiert. Kein zweites Thin-Event-Ziel oder Signatursecret wurde hinzugefügt.
Siehe [CLI-Flags](https://docs.stripe.com/cli/listen) und
[Connect-Ereignisquellen](https://docs.stripe.com/connect/webhooks).

**Manueller Zahlungsablauf in einer dedizierten Sandbox:**

1. Lokal registrieren, Mail in MailHog öffnen, E-Mail bestätigen und anmelden.
2. Im Profil Stripe-Test-Onboarding starten. Testkonten und Testdaten verwenden,
   erforderliche Capabilities vervollständigen und Accountstatus synchronisieren.
   Ein Rücksprung allein ist noch keine Freischaltung. Die vorhandenen Prüfungen
   für ausstehende Anforderungen, `card_payments`, Transfers und Empfängerstatus bleiben aktiv.
3. Im lokalen `docker`-Profil ist die produktive Teilnehmerbeschränkung nicht aktiv.
   Der Code liegt lokal vor und die vorhandenen Aktivierungsendpunkte bleiben nutzbar;
   der zusätzliche Freigabeschritt des `prd`-Profils ist hier nicht notwendig.
4. Den lokalen Mitarbeiter-Zahlungslink aus dem Profil in einem privaten Browserfenster
   öffnen. Es gibt keine neu erfundene öffentliche Mitarbeiter-Suchliste.
   Vordefinierten oder freien Betrag wählen (bestehende Grenze 1,00–10.000,00 EUR),
   optional Nachricht bis 500 Zeichen eingeben, Gebührenausgleich wählen und Zusammenfassung prüfen.
   Ein separater steuerlicher **Freibetrag ist nicht implementiert**; die UI benennt
   dies bereits. Freie Betragwahl und Freibetrag sind unterschiedliche Funktionen.
5. Zu Stripe Checkout gehen. [Testkarte](https://docs.stripe.com/testing)
   `4242 4242 4242 4242`, zukünftiges Ablaufdatum und beliebige dreistellige CVC verwenden.
   Keine echten Kartendaten eingeben. Nach Rückkehr wird Erfolg erst nach serverseitiger
   Stripe-Statusbestätigung angezeigt; die Redirect-URL allein bestätigt keine Zahlung.
6. Lokale Transaktion im Mitarbeiter-Dashboard und Webhook-Zustellung prüfen.
   `4000 0000 0000 9995` simuliert unzureichende Deckung. Eine Ablehnung ist noch keine
   bezahlte Session; beim Wiederholen müssen Betrag und Idempotenzverhalten stimmen.
   Ein geschlossenes Stripe-Fenster muss ohne Bestätigung ausstehend bleiben.
7. Optional Pools aktivieren, mehrere lokal registrierte und bei Stripe berechtigte
   Testempfänger einladen, per MailHog bestätigen, die vorhandenen Anteile konfigurieren
   und den lokalen Team-Link `/pay/team/<owner-id>` aus dem Dashboard verwenden.
   Separate Charges and Transfers und deren verzögerte Abrechnung sind implementiert.
   Transfers können bis zur Verfügbarkeit des Stripe-Testguthabens ausstehen.
   Auszahlungen bleiben simulierte lokale Buchungen; echte Bankauszahlungen werden nicht getestet.

Stripe erlaubt HTTP-Return-/Refresh-URLs im Testbetrieb:
[Hosted Onboarding](https://docs.stripe.com/connect/hosted-onboarding).
Falls die konkrete Sandbox für öffentliche Geschäftsprofil-URLs einen erreichbaren
Host verlangt, optional einen HTTPS-Tunnel ausschließlich auf Port 3001 einrichten,
`FRONTEND_BASE_URL` auf dessen eigene HTTPS-URL setzen und `up` erneut ausführen.
Beispiel nach Installation von Cloudflare Tunnel: `cloudflared tunnel --url http://localhost:3001`.
TLS- und Webhook-Signaturprüfungen bleiben aktiv. Keine produktive Domain oder
produktiven Stripe-Einstellungen ändern. Die lokale Business-URL wird bewusst nicht
als öffentliches Stripe-Geschäftsprofil eingetragen.

**Betrieb und automatisierte Tests:**

```bash
python3 docker-compose/stripe_test.py status
python3 docker-compose/test_stripe_local.py # nur nach erfolgreichem up
python3 docker-compose/stripe_test.py down  # lokale Daten bleiben erhalten
python3 -m unittest discover -s deployment/tests -p test_local_stripe_compose.py -v
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew \
  --project-cache-dir /tmp/gratilo-local-stripe-gradle-cache \
  :backend:user-service:test :backend:payment-service:test --no-daemon
(cd frontend && npm test)
```

Compose-Tests rendern mit Fakes und prüfen Netzwerk-/Datenbanktrennung, lokale
Redirects, Buildargumente, beide Webhook-Ziele und Ablehnung von Livekonfiguration.
Die vorhandenen Java-/Frontend-Tests verwenden kontrollierte Stripe-Fakes für
Checkout, Gebühren/Nachrichten, bestätigte/ausstehende/fehlgeschlagene Zahlung,
Signaturprüfung, wiederholte und verspätete Webhooks, Connect-Berechtigung,
Onboarding, Poolaufteilung, Transferabrechnung und Rückabwicklung. Neue Tests prüfen
die lokalen Redirects inklusive Stripe-Platzhalter und lokales Connect-Onboarding.
Das ersetzt keinen manuellen Sandbox-Kartentest mit einem vollständig freigegebenen
Connect-Testkonto. Keine erfolgreiche echte Sandboxzahlung wird aus einem Fake-Test abgeleitet.

Prüfergebnis dieser lokalen Erweiterung (20.10.2026): 90 Backend-Tests,
41 Frontend-Tests und 136 Python-Tests erfolgreich; Frontend-Build mit aktiviertem
Zahlungsmodus und lokalem Test-Publishable-Key erfolgreich. Der Standard-Dockerbuild
scheiterte hier an TLS-Handshake-Abbrüchen bei Gradle-/Micronaut-Paketdownloads.
Der dokumentierte Hostbuild war erfolgreich; PostgreSQL, beide Backends, Frontend,
MailHog und Stripe-Listener wurden lokal gestartet. Frontend und MailHog antworteten
auch unter Windows mit HTTP 200. Der lokale Smoke-Test bestätigte angewendete
Migrationen in beiden Schemas, geschützte Transaktions-API, Feature-Flags,
Signaturprüfung und Ablehnung von Live-Ereignissen sowie das passende Listener-Secret.
Der bestehende Secret Key wurde ausschließlich lesend als Sandbox-Key bestätigt.
Keine Kartenzahlung, kein Connect-Onboarding und kein Transfer gegen echte
Stripe-Sandbox-Objekte wurde automatisch durchgeführt; dafür die manuellen Schritte
oben mit eigenen lokalen Testbenutzern und einer dedizierten Sandbox ausführen.

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
`FEATURE_TIP_POOLS_ENABLED=false` sind die sicheren Standardwerte des normalen
Stacks. Für ausdrücklich gewählte lokale Pooltests kann das letzte Flag aktiviert werden.
Die Anwendung bricht bei Live-Schlüsseln oder unzulässiger Auszahlungskonfiguration ab.
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

Im Standardbetrieb sind Pools deaktiviert. Beide Backends verwenden dasselbe
`FEATURE_TIP_POOLS_ENABLED`-Flag. Der isolierte lokale Stripe-Stack kann damit
Poolverwaltung, Einladungen und Team-Checkout ausdrücklich freischalten.
Separate Charges and Transfers, verzögerte Abrechnung sowie Rückabwicklung sind
implementiert. Diese lokale Freischaltung verändert keine Produktionskonfiguration.
Bestehende Pooldaten und historische Buchungen bleiben erhalten.

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
