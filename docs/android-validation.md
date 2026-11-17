# Prüfprotokoll: Android und responsive Web-Oberfläche

Stand: Implementierung am 13.11.2026. Dieses Protokoll unterscheidet tatsächliche Läufe von noch offenen Prüfungen.

## Ausgeführt

| Prüfung | Ergebnis |
|---|---|
| `./gradlew :backend:user-service:test :backend:payment-service:test --console=plain` | Erfolgreich. XML-Berichte: 75 Suites, 381 Tests, davon 3 übersprungen. Mehrere Compile-Tasks kamen aus dem vorhandenen Gradle-Cache; beide Test-Tasks wurden ausgeführt. |
| `frontend: npm ci` | Erfolgreich. Bestehende npm-Audit-Meldungen: 1 moderate, 2 high, 2 critical; keine automatische Änderung der vorhandenen Produktabhängigkeiten vorgenommen. |
| `frontend: npm test` vor Änderungen | 186/187 erfolgreich; ein zeitabhängiger Test in `PoolTransactionHistory.test.tsx` schlug bei erwarteter inaktiver Mitgliedschaft fehl. |
| gezielter Wiederholungslauf `npx vitest run src/PoolTransactionHistory.test.tsx` | Alle 14 Tests erfolgreich. Keine Änderung an der Pool-Geschäftslogik zur Anpassung des Tests. |
| vollständiger Wiederholungslauf `frontend: npm test` | Alle 187 Tests in 24 Dateien erfolgreich. |
| `frontend: npm run build` | TypeScript und Vite-Produktionsbuild erfolgreich. |
| `frontend: npm run test:responsive` mit vorhandenem Chromium | Alle 10 Browser-Tests erfolgreich (320, 375, 768, 1440, 1920 px); öffentliche Seiten, Dashboard, Poolansicht, Transaktionsdialog, horizontale Überstände und Navigationsüberlappung. Testantworten sind lokal abgefangen; keine Stripe-Requests. |
| Browser-Screenshot | Poolansicht bei 320 px visuell geprüft. Zusätzliche Verbesserungen: Navigation passt in den Header, Copy-Buttons bleiben lesbar, Filterlabels und Verteilungssumme umbrechen nicht mitten im Wort. |
| Android `:app:tasks --no-daemon` | Erfolgreich; Gradle-/Plugin-Konfiguration geladen. Vorherige Versuche scheiterten an DNS-/Download-Zeitüberschreitungen. |
| Android `:app:dependencies --configuration debugCompileClasspath` | Erfolgreich; Abhängigkeitsgraph aufgelöst. Dies ist kein erfolgreicher APK-Build. |
| Android `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` | Erfolgreich nach Korrektur eines PDF-Ressourcenfehlers und der von Lint gemeldeten Debug-Netzwerkattribute. 15 JVM-Tests ohne Fehler; Debug-APK und Instrumentierungstest-APK gebaut. Instrumentierungstests wurden nur kompiliert, nicht ausgeführt. |
| XML-Parsing | Alle Android-Manifeste und Ressourcen-XML syntaktisch gültig. |
| `git diff --check` | Keine Whitespace-Fehler. |

## Android-Buildnachweis

Anfänglich waren weder Android SDK noch Emulator verfügbar. Die Commandline Tools sowie SDK 35 und Build Tools 35.0.0 wurden temporär unter `/tmp/gratilo-android-sdk` installiert. Zeitweilige DNS-/Downloadprobleme der öffentlichen Gradle-Repositories konnten für den Build umgangen werden; hierfür wurde keine Repository-Konfiguration committed.

Der tatsächliche abschließende Android-Lauf baut Debug-APK und Test-APK und führt alle 15 JVM-Tests und Lint erfolgreich aus. Ein zusätzlicher kalter Lint-Lauf (`lintDebug --rerun-tasks --no-build-cache --no-watch-fs`) ist ebenfalls erfolgreich: 0 Fehler, 6 Hinweise auf neuere Versionen der bewusst festgelegten AndroidX-Abhängigkeiten. Die APK liegt lokal unter `android/app/build/outputs/apk/debug/app-debug.apk`; das Verzeichnis ist ignoriert. Ein Emulator/AVD wurde nicht installiert, daher gibt es keinen ausgeführten Android-UI-/Keystore-Gerätetest und keinen Beleg für reale Backend-/Stripe-Integration auf einem Gerät.

## Zusätzlich lokal erforderlich

- Android `connectedDebugAndroidTest` auf einem laufenden Emulator/Gerät. Build, JVM-Tests, Lint und Kompilierung der Gerätetests sind bereits belegt.
- Backend-Integration auf Emulator und physischem Gerät mit lokal bestätigtem Testbenutzer.
- MailHog-Verifizierung/Passwortreset, Login nach App-Neustart, HTTP 401, Offline-Wiederholung und Abmeldung während laufender Abfragen.
- Alle Poolrollen, Owner-Deaktivierung, Einladung mit falschem Benutzer, CLOSING-/Settlement-Blocker und Auflösung mit realer lokaler Datenbank.
- Stripe-Testzugang, Onboarding-/Checkout-Rückkehr, Pending-/Failed-Status und sichere Wiederholung nach Prozessende; keine produktiven Zahlungen.
- Große Systemschrift, TalkBack, Bildschirmrotation, Android-Back-Navigation, Tastatur, Bilddateien und Dateiexporte.
- Produktions-App-Link-Verifikation und Release-Signatur; keine Infrastrukturänderung wurde dafür vorgenommen.
- PostgreSQL-abhängige Tests, die lokal übersprungen wurden, mit den bestehenden Fixtures/Scripts nachholen.

Browser-Berichte und Screenshots liegen unter `frontend/playwright-report` und `frontend/test-results` und werden nicht committed. Die neuen CI-Workflows wurden erstellt, aber noch nicht auf GitHub ausgeführt. Docker-/AWS-Deployment und produktive Stripe-Prozesse wurden nicht gestartet oder geändert.
