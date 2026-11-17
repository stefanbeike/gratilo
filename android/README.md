# Gratilo für Android

Eigenständiges Kotlin-/Jetpack-Compose-/Material-3-Projekt, Package `com.gratilo.app`, Android 8+ (API 26), Compile/Target SDK 35, JDK 17, Gradle 8.10, AGP 8.7.3, Kotlin/Compose-Compiler 2.0.21. Der Monorepo-Root-Build nimmt Android bewusst nicht als Subprojekt auf. Backend-, Docker- und AWS-Konfigurationen werden nicht verändert.

## Lokal starten

1. Android Studio mit Unterstützung für AGP 8.7 installieren. Im SDK Manager Android SDK Platform 35, Build Tools 35.0.0, Platform Tools und einen Emulator installieren. Ein API-35-Gerät anlegen. Für den Gradle-JDK JDK 17 auswählen.
2. Im Repository-Root die vorhandene `.env.example` nach `.env` kopieren, falls noch keine lokale `.env` existiert. Die erforderlichen lokalen Werte für `JWT_SECRET` und `INTERNAL_API_KEY` selbst erzeugen; keine produktiven Zugangsdaten verwenden. Für Pool-Tests `FEATURE_TIP_POOLS_ENABLED=true` setzen. Zahlungen bleiben zunächst ausgeschaltet.
3. Den bestehenden Standardstack starten (Docker Desktop/Engine muss laufen):

   ```bash
   docker compose --env-file .env -f docker-compose/docker-compose.infra.yml up -d
   docker compose --env-file .env -f docker-compose/docker-compose.services.yml up -d --build
   docker compose --env-file .env -f docker-compose/docker-compose.services.yml ps
   ```

   Backend: Host-Port 8081 und 8082; Web: 3000; MailHog: http://localhost:8025. Der Infra-Stack legt das von den Services benötigte `gratilo-network` an. Vorhandene lokale Datenbanken/Benutzer werden wiederverwendet. Es gibt keine in Android eingebauten Testbenutzer oder Passwörter.
4. In Android Studio **den Ordner `android/`**, nicht den Repository-Root, öffnen. Gradle synchronisieren; `local.properties` wird lokal mit dem SDK-Pfad erzeugt und bleibt ignoriert.
5. Emulator starten und die Run-Konfiguration `app` als Debug-App ausführen. Standardziele:

   | Service | Android-Debug-URL |
   |---|---|
   | User | `http://10.0.2.2:8081/` |
   | Payment | `http://10.0.2.2:8082/` |
   | Web für QR-Links | `http://10.0.2.2:3000/` |

6. Mit einem vorhandenen, bestätigten lokalen Benutzer anmelden. Alternativ in der App registrieren, die E-Mail in MailHog öffnen und den Bestätigungslink im Web ausführen. Danach in der App anmelden. Fehlende Bestätigung führt wie im Web zu HTTP 403.
7. Profil ändern, persönliche Links aktivieren/pausieren, Transaktionen laden, Pools anlegen/einladen, Gewichte speichern und Mitgliedschaft pausieren testen. Stripe-Funktionen benötigen die vorhandene Stripe-Sandbox-Konfiguration und freigeschaltete Konten; ein leerer Standardstack erzeugt keine Zahlungsbereitschaft.

Windows: `gradlew.bat` statt `./gradlew`. WSL und Android Studio können unterschiedliche SDK-Installationen verwenden. Für Linux-Gradle ein Linux-SDK verwenden; Android Studio unter Windows nutzt das Windows-SDK. Lokale API-Ports müssen vom Emulator-Host erreichbar sein.

## Physisches Gerät / andere URLs

Per USB mit aktivierter Entwickleroption USB-Debugging:

```bash
adb reverse tcp:8081 tcp:8081
adb reverse tcp:8082 tcp:8082
adb reverse tcp:3000 tcp:3000
cd android
./gradlew :app:assembleDebug -PuserUrl=http://127.0.0.1:8081/ -PpaymentUrl=http://127.0.0.1:8082/ -PwebUrl=http://127.0.0.1:3000/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Alternativ im persönlichen `~/.gradle/gradle.properties` `userUrl`, `paymentUrl`, `webUrl` setzen. URLs brauchen einen abschließenden Slash. Debug-HTTP ist auf `10.0.2.2`, `localhost` und `127.0.0.1` begrenzt. Für LAN-Geräte HTTPS oder `adb reverse` verwenden. Keine globale HTTP-Freigabe für beliebige Hosts hinzufügen. QR-Links mit `10.0.2.2` sind nur im Emulator erreichbar; für andere Nutzer eine von ihnen erreichbare HTTPS-Web-Adresse konfigurieren. Das Backend liefert Links anhand seines eigenen `FRONTEND_BASE_URL`; diese Einstellung muss ebenfalls zum Testnetz passen.

## Stripe-Sandbox

Die bestehenden Abläufe sind in [README.adoc](../README.adoc) und den [Sandbox-Unterlagen](../docs/stripe-sandbox-single-pool-test.md) beschrieben. Der isolierte Stack wird so vorbereitet:

```bash
python3 docker-compose/stripe_test.py init
# .env.stripe-test ausschließlich lokal mit Stripe-Testschlüsseln konfigurieren
python3 docker-compose/stripe_test.py check-stripe
python3 docker-compose/stripe_test.py webhook-secret
python3 docker-compose/stripe_test.py check
python3 docker-compose/stripe_test.py up
```

Dieser Stack veröffentlicht die API hinter dem Web-Proxy auf Host-Port 3001, nicht direkt auf 8081/8082. Android damit bauen:

```bash
cd android
./gradlew :app:assembleDebug -PuserUrl=http://10.0.2.2:3001/ -PpaymentUrl=http://10.0.2.2:3001/ -PwebUrl=http://10.0.2.2:3001/ -PpaymentsEnabled=true
```

`paymentsEnabled` ist standardmäßig `false`, analog zum Web-Checkout-Feature. Der Backend-Zahlungsstatus bleibt maßgeblich. Stripe Connect und Checkout öffnen ausschließlich HTTPS-URLs der Hosts `connect.stripe.com` beziehungsweise `checkout.stripe.com` im externen Browser. Keine WebView und keine Stripe-Secret-Keys in Android. Nach Connect-Rückkehr „Stripe-Status beim Backend prüfen“ wählen. Zahlungen werden nur durch `/tips/checkout/confirm` bestätigt. Die Browser-Rückkehr allein beweist keine Zahlung.

## Deep Links

Unterstützt werden HTTPS-App-Links für `/pay/*`, `/pool/invitations/*`, `/auth/verify?token=…` und `/reset-password?token=…`. Den tatsächlichen Webhost mit `-PappLinkHost=…` konfigurieren; `gratilo.de` ist nur der vorgegebene Hostwert, keine Zusage einer vorhandenen Domain-Verifikation. Für automatische Produktionslinks muss der Domainbetreiber `/.well-known/assetlinks.json` mit Package und Signaturfingerprint veröffentlichen. Diese Aufgabe verändert keine Domain oder AWS-Ressource.

Lokale Links ohne Domain-Verifikation gezielt an die Debug-App senden:

```bash
adb shell am start -a android.intent.action.VIEW -d 'http://10.0.2.2:3000/pay/DEIN_LINK' -n com.gratilo.app.debug/com.gratilo.app.MainActivity
adb shell am start -a android.intent.action.VIEW -d 'https://gratilo.de/pool/invitations/DEIN_TOKEN' -n com.gratilo.app.debug/com.gratilo.app.MainActivity
adb shell am start -a android.intent.action.VIEW -d 'https://gratilo.de/auth/verify?token=DEIN_TOKEN' -n com.gratilo.app.debug/com.gratilo.app.MainActivity
adb shell am start -a android.intent.action.VIEW -d 'https://gratilo.de/pay/success?session_id=DEINE_SESSION' -n com.gratilo.app.debug/com.gratilo.app.MainActivity
```

Einladungen bleiben während der Anmeldung in der Navigation erhalten. Bei lokalen Stripe-Redirects auf `localhost` öffnet der Browser standardmäßig die Web-App; die Ergebnis-URL kann mit dem letzten Befehl an Android übergeben werden. Produktiv lösen verifizierte App-Links diesen Wechsel.

## Build und Tests

```bash
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# Auf laufendem Emulator/Gerät:
./gradlew :app:connectedDebugAndroidTest
# Unsigniertes Release (alle URLs müssen explizit HTTPS sein):
./gradlew :app:assembleRelease -PreleaseUserUrl=https://DEIN_API_HOST/ -PreleasePaymentUrl=https://DEIN_API_HOST/ -PreleaseWebUrl=https://DEIN_WEB_HOST/ -PappLinkHost=DEIN_WEB_HOST
```

Debug-APK: `app/build/outputs/apk/debug/app-debug.apk`. Debug-Package: `com.gratilo.app.debug`; Release: `com.gratilo.app`. Release erlaubt kein Cleartext-HTTP. Die Release-URLs werden zusätzlich zur Build-Prüfung beim App-Start geprüft. Signierte Releases über Android Studio „Generate Signed Bundle / APK“ mit einem extern verwahrten eigenen Keystore erstellen. Signing-Daten gehören nicht ins Repository. Die Android-CI baut Debug, führt JVM-Tests und Lint aus und stellt APK und Berichte bereit. Kein Play-Upload, keine Abhängigkeit von Backend-Releases.

Web-Prüfungen:

```bash
cd frontend
npm ci
npm test
npm run build
npx playwright install chromium
npm run test:responsive
```

Die Browser-Tests verwenden abgefangene Testantworten; sie lösen keine Zahlungen aus. `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` erlaubt einen vorhandenen Chromium-Pfad. Unter WSL auf Windows-Dateisystemen kann Vite Dateiänderungen übersehen: Dev-Server nach CSS-Änderungen neu starten.

Backend-Prüfung aus dem Root: `./gradlew :backend:user-service:test :backend:payment-service:test`. Datenbankgestützte Integrationsprüfungen benötigen zusätzlich die vorhandenen PostgreSQL-Fixtures; siehe deren bestehende Scripts und Workflow-Konfiguration.

## Struktur und Architektur

- `app/src/main/java/com/gratilo/app/MainActivity.kt`: Composition Root, ViewModel-Factory, eingehende Intents.
- `data/Api.kt`: Retrofit/OkHttp, Services nach Origin getrennt, Repository-Vertrag und einheitliche `ApiFailure` mit Status/Backend-Code.
- `data/SecureSessionStore.kt`: AES-GCM mit Android-Keystore, nur Ciphertext in privaten Preferences, Backups deaktiviert. Keine Passwortpersistenz, keine Token-Logs.
- `ui/GratiloViewModel.kt`: MVVM, Coroutines, serialisierte Requests und `StateFlow<UiState>`. Authentifizierungszustand kommt aus dem Repository. HTTP 401 entfernt die Sitzung; Logout verwirft laufende UI-Jobs und Profildaten. Kein erfundener Refresh-Token-Endpunkt.
- `ui/*Screens.kt`: native Formulare, Bestätigungsdialoge, Pool- und Kontoansichten.
- `ui/PublicPayment.kt`: öffentliche Empfänger, Backend-Angebot und Hosted Checkout; gleiche Idempotenz-ID bei Wiederholung. Unklare Zahlungs-/Auszahlungsversuche bleiben verschlüsselt erhalten.
- `ui/ProfilePhoto.kt`: System-Bildauswahl, Dateityp/5-MB-Prüfung, quadratischer 256-Pixel-JPEG für den vorhandenen Validator.
- `ui/ExportTransactions.kt`: CSV/PDF über Androids Dokumentauswahl, keine Speicherberechtigung.
- `ui/Theme.kt`: Produktfarben und Material-3-Rundungen.
- `src/test`: ViewModel- und MockWebServer-Vertragstests; `src/androidTest`: Compose-UI-Prüfungen.

Die bestehende Backend-API liefert uneinheitliche, teilweise optionale Payment-Snapshots. Der Client hält sie als Gson-JSON-Objekte, ohne Gebühren oder Poolverteilung nachzubauen. Für eine größere Weiterentwicklung sollten fachlich getrennte, typisierte Screen-States und DTOs eingeführt werden; aktuell gibt es einen gemeinsamen ViewModel mit separaten Screen-Dateien.

## Funktionsumfang und Grenzen

Das detaillierte [Funktionsinventar](../docs/android-feature-inventory.md) beschreibt Web-Quellen, Rollen, Endpunkte und Unterschiede. [Prüfprotokoll](../docs/android-validation.md) dokumentiert ausschließlich tatsächlich ausgeführte Prüfungen.

Noch keine vollständige Web-Parität: Landingpage/Kontaktformular und NFC-Werbematerialien bleiben im Web; native Transaktionen bieten Suche, Typ-/Zeitraumfilter und eine feste 50er-Paginierung; die Web-Auswahl von 100/200 Zeilen und die vollständige Gebühren-/Metadatenaufbereitung fehlen. Pool-Anteile sind Textlisten statt interaktivem Verteilungsdiagramm. Bildauswahl nutzt den mittigen Zuschnitt statt Web-Bildeditor und berücksichtigt noch keine EXIF-Rotation. QR-Codes können als PNG gespeichert und Links geteilt werden. Stripe-Status wird auf Anforderung aktualisiert; Web-Polling und sämtliche Checkout-Konflikt-/Resume-Dialoge sind noch nicht vollständig übernommen. Systemkamera-/QR-Scanner ist kein vorhandenes Web-Feature und wird nicht eingebaut.

Vor einer Veröffentlichung müssen Emulator-/Gerätetests, reale Backend-Integration mit bestätigten Testbenutzern, Stripe-Sandbox-Rückkehr, Prozessneustart bei unklarem Checkout/Payout, TalkBack, große Schrift und App-Link-Verifikation geprüft werden. Es wurden keine produktiven Stripe-Zahlungen oder AWS-Änderungen vorgenommen.

Build-Kompatibilität: [Compose-Compiler-Konfiguration](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler), [Kotlin-Compose-Migration](https://kotlinlang.org/docs/compose-compiler-migration-guide.html).
