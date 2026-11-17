# Gratilo – Funktionsinventar und Android-Abgleich

Analysegrundlage: vorhandenes React/TypeScript-Frontend unter `frontend/src`, Micronaut-Controller/Request-/Response-Records beider Services, Gradle-/Docker-/Workflow-Konfigurationen und bestehende Tests. Keine neue Backend-Geschäftsfunktion und kein neuer Endpunkt wurde eingeführt.

## Repository und Zuständigkeiten

| Bereich | Vorhandene Struktur / Vertrag |
|---|---|
| Root | Gradle 8.10; `settings.gradle` bindet nur `backend:user-service`, `backend:payment-service`, `frontend` ein; Backend-Java-Toolchain 21 |
| User-Service | Konten, JWT-Ausstellung, Verifizierung/Reset, Profil-/Linkverwaltung, Feature-Flags, Pool-Rollen, Einladungen und Auflösung |
| Payment-Service | Stripe Connect/Checkout, Status-/Gebührenabgleich, unveränderliche Zahlungssnapshots, Pooltransfers, Auszahlungsvoraussetzungen und Auszahlungen |
| Frontend | React 19 / TypeScript / Vite, eigene CSS-Dateien, lokale Session-Persistenz, SVG-Branding, `qrcode.react`, jsPDF |
| Docker | Infra-Netzwerk + Standardservices; zusätzlicher isolierter Stripe-Sandboxstack mit eigenem Compose-Projekt und Datenbank |
| Deployment | Bestehende AWS-/Release-Workflows bleiben unverändert; Android und responsive Browserprüfungen haben separate Workflows |
| Android | Eigenständiges Projekt unter `android/`, MVVM/Repository/StateFlow/Coroutines, native Material-3-Komponenten |

## Seiten, Navigation und Rollen

| Web-Bereich / Quellen | Funktion, Regeln und Wege | Android-Stand |
|---|---|---|
| `/`, `LandingPage`, `LandingProducts`, `LandingContact`, `LandingIcon`, `landing.css` | Produktbeschreibung, zahlungsfreie Demo, Einzel-/Poolerklärung, QR-/NFC-Materialien, FAQ, Preise, Kontaktformular mit Pflichtfeldern, Honeypot und Backend-Versand; Sprunglinks nach Produkten/Material/Preisen/Kontakt, Login/Registrierung | Marketing und Kontakt bleiben Web-Inhalte; kein vorgetäuschter nativer Kontaktversand |
| `/login`, `App` | E-Mail/Passwort, JWT, `/users/me`, Sitzung abgelaufen, automatische Rückkehr zur Einladung | Native Anmeldung, sichere Token-Persistenz, Backend-401 meldet ab; Einladung bleibt erhalten |
| `/register`, `App`, `profileImage` | optionale Namen/Bild, Newsletter, verpflichtende Test-AGB, E-Mail max. 254, Passwort mindestens 8 Zeichen, Unicode Groß-/Kleinbuchstabe + Sonderzeichen, keine Leerzeichen, Wiederholung | Native Registrierung, identische Passwortrichtlinie und Testtext-IDs, Systembildauswahl |
| `/forgot-password`, `/reset-password`, `/resend-verification`, `/auth/verify` | Resetanforderung, Tokenlink und neues Passwort, erneuter Versand mit E-Mail und Passwort, generische Versandbestätigung, Verifizierung | Native Formulare und Token-Intents; Erfolgsmeldungen aus Backend |
| `/account`, `Dashboard`, `PersonalAccountPanel` | Persönliche/Pool-Ansicht, unabhängig persönliches Stripe-Guthaben und Auszahlungen; heutige/7-/30-Tage-Statistiken; persönlicher Link aktiv/pausiert; QR nur nach Stripe-Freigabe | Native Übersicht, Backend-Summary, persönlicher QR/Link, Profil, Pools und Stripe-/Auszahlungsscreens; Datumsstatistiken unterscheiden sich noch |
| Profil in `Dashboard`, `TeamImageEditor` | Vor-/Nachname, Organisation, Telefon, Profilbild, Passwortänderung, Konto schließen mit Bestätigung; Backend kann Pools als Schließhindernis melden | Native Profilfelder inkl. Adresse/Land/Newsletter und Passwortänderung, Bildauswahl, Kontoabschlussdialog |
| `Dashboard`, `StripeOnboarding`, `StripeStatusCard`, `StripeTestEnrollment`, `poolStripeStatus` | zentraler Connect-Status, aktuelle Freigaben/Statusalter, Hosted-Onboarding, Backend-Sync, Testzugang aktivieren/deaktivieren | Native Statuskarten, Hosted-Browser, expliziter Sync und Testzugang; kein automatisches Web-Polling |
| `Dashboard`, `PoolMembersPanel`, `PoolDistributionChart` | Poolinhaber verwaltet mehrere Pools; Mitglied kann mehreren Pools angehören; vollständige Inhaberantwort oder eingeschränkte Mitgliedsantwort; keine Verwaltung aus historischer Zahlung ableiten | Eigene Poolwahl/Detailabfrage, Rechte aus Backend-Owner-ID, Mitglieder und effektive Anteile; historische Transfers separat |
| Pool-Verwaltung | Erstellen/Umbenennen (max. 120), Bild, vorhandenen Nutzer per E-Mail einladen, keine Selbsteinladung, erneut senden, Gewicht 0–100 mit zwei Dezimalstellen, mehrere Gewichte atomar speichern, Mitglied aktiv/deaktivieren/entfernen, Stripe-Freigaben prüfen | Native Formulare und Aktionen; Backend berechnet relative Gewichte; keine lokale Pool-Gebühren-/Verteilungslogik |
| Pool-Mitgliedschaft | Einladung ansehen/annehmen/ablehnen, pausieren/reaktivieren, verlassen; vom Owner deaktivierte Mitglieder dürfen sich nicht selbst aktivieren | Native Mitgliedschaftsaktionen, Backend prüft Rechte; Bestätigungsdialog vor Austritt/Entfernung |
| `/pool/invitations/{token}`, `PoolInvitation`, `PoolInvitationLogin` | Anmeldung/Registrierung vor Antwort, E-Mail-/Teilnehmerprüfung im Backend, abschaltbares Feature | Native Anmeldung vor Antwort und Prüfung von `/features` |
| `PoolDissolveDialog` | Poolname exakt bestätigen; offene Settlement-/Transferfälle können Auflösung blockieren; `CLOSING` sperrt neue Zahlungen/Mutationen; historische Ansprüche bleiben | Native Namensbestätigung, Backend-Auflösung und Fehlermeldung; keine vollständige Darstellung aller strukturierten Blockerdetails |
| Transaktionen in `Dashboard` | persönliche Zahlungen vs. historische Pooltransfers, Referenzsuche, Typ-/Zeitraumfilter, 50/100/200-Zeilen-Paginierung, CSV/PDF; Detaildialog mit unveränderlichen Brutto-/Netto-/Steuer-/Gebühren-/Statussnapshots, Istkostenabgleich | Native Suche, Typ-/Zeitraumfilter, feste 50er-Paginierung, CSV/PDF, Kerndetails und historische Poolliste; Web-Metadatenaufbereitung und auswählbare Seitengrößen noch nicht vollständig portiert |
| `PayoutManager` | Voraussetzungen/Bank prüfen, mindestens EUR 1 bis tatsächlich verfügbar, lokale Simulation oder Stripe-Testpayout, ungeklärte Anfrage mit gleicher UUID wiederholen, keine zweite Anfrage bei Pending/Authorized, manuell abgleichen | Native Voraussetzungen/Bestätigung/Anforderung/Historie/Abgleich, verschlüsselte Idempotenzpersistenz; Wiederholungen müssen denselben Betrag nutzen |
| `/pay/{path}`, `/pay/team/{ownerOrPool}`, `PublicTipCheckout`, `DemoPayment` | öffentliche Profile/Poollinks und ältere UUID-Links; Backend-Eligibility/Poollimits/Quote; EUR-Betrag, Gebührübernahme, Nachricht max. 500; erwartete Quote/Pool-ID und persistente Idempotenz-ID; Hosted Stripe Checkout | Native Empfängerabfrage/Backend-Quote und Hosted Checkout, standardmäßig abgeschaltet; Basisvalidierung plus maßgebliche Backend-Prüfungen; noch keine vollständigen Web-Konflikt-/Resume-Dialoge |
| `/pay/success?session_id=…`, `/pay/cancelled` | Backend bestätigt Zahlung; Pending ist kein Erfolg, erneut prüfen statt erneut zahlen; Pool-/Personenzuordnung aus bestätigter Antwort | Native explizite Statusprüfung aus Rückkehr-Link; keine Erfolgsmeldung allein aufgrund Redirect |

Gäste, angemeldete Benutzer, Poolinhaber und Poolmitglieder werden nicht als neue globale Serverrollen erfunden. Poolrechte ergeben sich aus authentifizierter User-ID, Pool-Owner-ID und der vom Backend gelieferten Mitgliedschaft. Feature-Flags sind unbekannt/aktiv/inaktiv unterscheidbar. Historische Transfers erzeugen keine aktuellen Verwaltungsrechte.

## Verwendete öffentliche / authentifizierte Endpunkte

Pfadpräfixe sind Bestandteil der Controller; Android ruft sie direkt auf. Authentifizierte Aufrufe tragen `Authorization: Bearer <accessToken>`. DTO-Quellen: `UserRequests`, `UserResponses`, `PoolRequests`, `PoolResponses`, `TipPaymentRequest`, `TipQuote`, `CheckoutResponse`, `TransactionResponse`, `PayoutRequest`, `ConnectedAccountResponse`.

| Service / Methode | Pfad | Verwendung |
|---|---|---|
| User POST | `/user-service/auth/login` | `{email,password}` → JWT |
| User POST | `/user-service/users/register` | Register-Record, AGB-/Datenschutz-IDs, optionaler JPEG-Data-URL |
| User GET | `/user-service/users/me` | Profil nach Login/Wiederherstellung |
| User PUT / DELETE | `/user-service/users/profile`, `/user-service/users/account` | Profil bzw. Konto schließen |
| User POST | `/user-service/auth/password-reset/request`, `/confirm` | E-Mail bzw. `{token,newPassword}` |
| User POST / GET | `/user-service/auth/verification/resend`, `/user-service/auth/verify?token=…` | Bestätigungs-E-Mail / Aktivierung |
| User GET | `/user-service/features` | `tipPoolsEnabled` |
| User GET | `/user-service/pools/all`, `/user-service/pools/{id}` | Poolliste / rollengerechte Details |
| User POST / PUT | `/user-service/pools`, `/user-service/pools/{id}` | `{name}` erstellen / umbenennen |
| User PUT | `/user-service/pools/{id}/image`, `/shares` | `{profileImage}`, `{values:[{memberId,sharePercent}]}` |
| User POST | `/user-service/pools/{id}/members`, `/members/{member}/resend` | Einladen / erneut senden |
| User PUT / DELETE | `/user-service/pools/{id}/members/{member}?active=…` | Aktivierung / Entfernung (DELETE ohne Query) |
| User PUT / DELETE | `/user-service/pools/memberships/{member}?active=…` | eigene Mitgliedschaft (DELETE ohne Query) |
| User POST | `/user-service/pools/invitations/{token}/accept`, `/decline` | Einladungsantwort |
| User POST | `/user-service/pools/{id}/dissolve` | `{poolName}` |
| User GET | `/user-service/users/public/{uuid}`, `/public/payment/{path}`, `/user-service/pools/public/{ownerOrPool}` | öffentliches persönliches/Pool-Zahlungsziel |
| Payment GET | `/payment-service/transactions`, `/transactions/summary`, `/transactions/{id}/fees` | persönliche Liste / Summary / Gebührenabgleich |
| Payment GET | `/payment-service/pool-transfers` | gespeicherte historische Anteile |
| Payment GET / POST | `/payment-service/connect-account`, `/connect-account/sync` | Stripe-Konto / aktueller Backend-Abgleich |
| Payment POST | `/payment-service/connect-account/onboarding/start` | `onboardingUrl` vom Backend |
| Payment GET | `/payment-service/connect-account/balance`, `/connect-account/pools/{id}/members` | Guthaben / Mitgliederstatus |
| Payment GET / POST / DELETE | `/payment-service/test-access` | Testfreischaltung |
| Payment GET | `/payment-service/payouts/eligibility`, `/payouts` | Auszahlungsvoraussetzungen / Historie |
| Payment POST | `/payment-service/payouts/setup`, `/payouts`, `/payouts/{id}/reconcile` | Vorbereitung, `{requestId,amountMinor}`, Abgleich |
| Payment GET | `/payment-service/tips/{id}/eligibility`, `/tips/pools/{id}/eligibility` | persönliche / Team-QR-Freigabe und Prüfung vor dem Angebot |
| Payment GET | `/payment-service/tips/{id}/quote`, `/tips/pools/{id}/quote` | `amountMinor`, `feeCovered`; Backend berechnet Gebühren |
| Payment POST | `/payment-service/tips/{id}/checkout`, `/tips/pools/{id}/checkout` | vorhandener `TipPaymentRequest` inkl. erwarteter Quote und Pool-ID |
| Payment GET | `/payment-service/tips/checkout/confirm?sessionId=…` | einziger Beleg für Zahlungsbestätigung |

Weitere vorhandene Web-Endpunkte: `POST /user-service/contact`, `/tips/pool-limits`. Ihre erweiterten UI-Abläufe sind noch nicht vollständig nativ übernommen. Interne Service-Endpunkte (`/internal/*`), Stripe-Webhooks und Secret-basierte Prüfpfade sind keine Android-APIs.

## Gemeinsame visuelle Tokens

| Token | Web | Android |
|---|---|---|
| Primär | `--gratilo-primary: #245a88` | `GratiloBlue` / Material primary |
| Sekundär | `#123f46` | Material secondary |
| Mint / Pink | `#43e5ce` / `#f326a5` | `GratiloMint` / `GratiloPink` |
| Oberfläche / Hintergrund | Weiß / `#f5f8fb` | Material surface/background |
| Fehler | `#a54545` | Material error |
| Abstände | 8 / 16 / 24 px | 8 / 16 / 24 dp |
| Rundungen | 8 / 16 / 24 px | Material shapes 8 / 16 / 24 dp |
| Typografie | bestehende Arial-/Sans-Serif-Hierarchie, einzelne Serif-Akzente | native Material-Sans-Serif-Hierarchie; kein eingebetteter Fremdfont |
| Bedienflächen | mobile Mindesthöhe 44 px | Material-Komponenten und primäre Aktionen mindestens 48 dp |

Die Web-Token-Datei ergänzt die bestehenden Styles. Die ursprünglichen Marken-/Desktopregeln bleiben bestehen. Mobile Tabellen werden als gestapelte Zeilen dargestellt; semantische Tabellenüberschriften bleiben für assistive Technik im DOM. Für die finale Freigabe ist ein Screenreader-Test des CSS-Tabellenlayouts erforderlich.
