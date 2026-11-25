# Stripe-Auszahlungen bei Gratilo

Ursprünglicher Test vom 25. November 2026 · Lokale Stripe-Sandbox `acct_1UFbtVCMj7Vzvm34` · API `2026-09-30.endive`

## Korrektur zur Sandbox und zu den Dashboard-Links

**Die drei Auszahlungen aus dem ursprünglichen Bericht gehören zur lokalen Sandbox `acct_1UFbtVCMj7Vzvm34`. Für die Abnahme des bereitgestellten Gratilo-Deployments wurde damit in der falschen Sandbox getestet.** Die frühere Prüfung des laufenden ECS-Services und des Deployment-Secrets identifizierte `acct_1UQNErJnhDgmiMAM` als dessen Sandbox. Die aktuelle ECS-Revision, ihr wirksamer Payout-Modus und ihr Stripe-Secret konnten in dieser Nachprüfung aufgrund abgelaufener AWS-SSO-Anmeldung nicht erneut geladen werden. `/user-service/features` auf `gratilo.eu` bestätigte lediglich, dass das Pool-Feature aktiv ist. [Früherer ECS-Abgleich](manual-sandbox-payouts-automation.adoc) · [Nachweis der Deployment-Sandbox](stripe-sandbox-single-pool-test.md#zugang-empfänger-und-schutzmaßnahmen)

| Test | Connected Account der lokalen Sandbox | Stripe-Profil | Payout-ID | Erneuter Abruf mit `Stripe-Account` | Kontrolle ohne `Stripe-Account` |
| --- | --- | --- | --- | --- | --- |
| Einzel | `acct_1UeyYlCMj7ljPTXn` | Stefan Beike · Gratilo-Kellner | `po_1UfgOOCMj7ljPTXnG56HbpG9` | 200 · `paid` · 4,72 EUR · `req_IUIB4G1IVfmeaJ` | 404 · `resource_missing` · `req_kIm2mDieFL0eNm` |
| Pool 1 | `acct_1UJFmGCMj7iPU5TL` | Stefan Beike · Tipster-Kellner | `po_1UfgOOCMj7iPU5TLlIvypmN1` | 200 · `paid` · 2,36 EUR · `req_ALovBbLeZfvhUI` | 404 · `resource_missing` · `req_ce8d0YGDpegRFl` |
| Pool 2 | `acct_1UGFJ3CMj7h4O2B5` | Stefan Beike · Tipster-Kellner | `po_1UfgOOCMj7h4O2B5RH3ZoNvi` | 200 · `paid` · 2,36 EUR · `req_PjDDtwpZGC26Hw` | 404 · `resource_missing` · `req_CoZatNJJn9tSgA` |

Der lokale Plattformkontext ist durch `GET /v1/account` (`req_J7tXlGh5QlwQsR`) und `GET /v1/balance` (`req_CSPDhTctOCfteK`) bestätigt. Stripe meldete für alle drei Objekte `livemode=false` und `automatic=false`. Die Namen stammen aus den Stripe-Testprofilen. Ein Firmenname war bei keinem der drei Stripe-Profile hinterlegt. Im ursprünglichen Test wurden die Zahlungszuordnungen für den lokalen Worker angelegt, ohne bestehende Gratilo-Mitarbeiter- oder Betriebsdatensätze zu verwenden. Eine Betriebszuordnung ist daher für diese drei Testvorgänge nicht belegt. [Stripe zum Connected-Account-Kontext](https://docs.stripe.com/connect/authentication) · [Payout abrufen](https://docs.stripe.com/api/payouts/retrieve)

### So findest du die drei älteren Auszahlungen

Im [Stripe-Dashboard](https://dashboard.stripe.com/) die Sandbox der Plattform `acct_1UFbtVCMj7Vzvm34` auswählen. Unter **Connect → Auszahlungen** nach dem Connected Account aus der Tabelle filtern und die Auszahlung anhand der Payout-ID öffnen. Alternativ unter **Connect → Konten** nach der Connected-Account-ID suchen und von dessen Kontoübersicht zu **Auszahlungen** wechseln. Die Konten mit dem Anzeigenamen „Tipster-Kellner“ lassen sich über ihre Konten-IDs unterscheiden. In der Deployment-Sandbox sind diese IDs kein Nachweis. Die zuvor angegebenen Stripe-Objektlinks wurden entfernt. Die URL-Varianten führten ohne Stripe-Dashboard-Anmeldung zum Login. Eine funktionierende authentifizierte Direkt-URL konnte hier nicht verifiziert werden. [Stripe zur Dashboard-Ansicht für Connect-Auszahlungen](https://support.stripe.com/questions/troubleshooting-missing-payouts-for-connected-accounts)

### Verifikation über das bereitgestellte Mitarbeiterdashboard

Der vorhandene Auszahlungspfad ruft als angemeldeter Mitarbeitender `POST /payment-service/payouts` mit einer eindeutigen Request-ID auf. Das Deployment verwendet beim üblichen Stripe-Test-Release die lokale Simulation (`STRIPE_SANDBOX_PAYOUTS=true`). Stripe-Test-Payouts über Gratilo benötigen das gesonderte, explizit bestätigte Release-Opt-in `STRIPE_TEST_PAYOUTS_ENABLED`. Der Renderer setzt dann `STRIPE_SANDBOX_PAYOUTS=false` und `STRIPE_MANUAL_PAYOUTS_API_APPROVED=true`; der Auszahlungsservice setzt außerdem einen bereiten Connected Account mit EUR-Bank, verfügbaren Testbeträgen und bestätigtem manuellem Plan voraus. Der zuletzt in der früheren Untersuchung dokumentierte ECS-Modus war Simulation. Ob die laufende Revision noch diesen Modus verwendet, bleibt bis zum ECS-Abgleich offen. [Deployment-Renderer](../deployment/scripts/render_task_definition.py) · [Payout-Controller](../backend/payment-service/src/main/java/com/gratilo/payment/controller/PayoutController.java)

In der Auszahlungshistorie des Mitarbeiterdashboards wird jetzt bei Stripe-Auszahlungen die bereits vom Backend gelieferte Payout-ID angezeigt und ist kopierbar. Lokale Simulationen tragen eine eindeutige Kennzeichnung ohne Stripe-Referenz. 21 gezielte Frontend-Tests und der Frontend-Build waren erfolgreich. Die lokale Änderung ist noch kein Nachweis für die bereitgestellte Anwendung. [PayoutManager](../frontend/src/PayoutManager.tsx)

**Die Deployment-Abnahme steht aus.** Nach `aws sso login --profile gratilo` muss die aktive ECS-Task-Definition samt Stripe-Secret geprüft werden. Danach nur in der bestätigten Deployment-Sandbox einen freigegebenen Testmitarbeiter samt Betrieb und Connected Account zuordnen, ein noch unverbrauchtes Testguthaben bestätigen und die Auszahlung einmalig über das Gratilo-Mitarbeiterdashboard auslösen. Der Nachweis umfasst die echte `po_…`-ID aus der Gratilo-Historie, denselben Datensatz im Connected-Account-Dashboard, Betrag, Währung und Status. Bei unklarem Ergebnis wird derselbe Auftrag abgeglichen. In dieser Nachprüfung wurde kein neuer Payout angelegt.

## Ergebnisse des ursprünglichen Tests in der lokalen Sandbox

- **Plattform.** Stripe meldet `payouts_enabled=false` und `payments.payouts.status=disabled`. Vor den neuen Tests waren 17.466,47 EUR verfügbar und 0 EUR ausstehend. Danach waren 17.467,54 EUR verfügbar (HTTP 200, `req_AzVk96sbmM7sHf`). Der konfigurierte tägliche Plan aktiviert Auszahlungen nicht. Für die Bankverbindung gab die API `403 more_permissions_required` zurück. Welcher konkrete Freigabe- oder Banknachweis für die Plattform noch fehlt, muss im Stripe-Dashboard geprüft werden.
- **Einzelzahlungen.** Der frische Direct Charge war erfolgreich. 4,72 EUR waren auf dem korrekten Connected Account verfügbar. Der tägliche Stripe-Lauf hatte beim ersten Abruf noch kein Payout für das Testguthaben angelegt. Eine anschließend einmalig über die Test-API angestoßene Standardauszahlung ist in Stripe mit Status `paid` sichtbar. Ein älterer anderer Direct Charge blieb nachweislich wegen offenen Onboardings auf dem Empfängerkonto. Dort waren 93,60 EUR verfügbar, Auszahlungen waren deaktiviert.
- **Pool-Zahlungen.** Der frische Pooltest wurde auf der Plattform gebucht. Der Gratilo-Worker erstellte zwei quellgebundene Transfers über je 2,36 EUR. Beide Empfänger hatten den Betrag verfügbar. Beim ersten Abruf lagen für diese Gutschriften noch keine Payouts vor. Zwei anschließend über die Test-API angestoßene Standardauszahlungen sind in Stripe jeweils mit Status `paid` sichtbar. Automatische Auszahlungen funktionieren grundsätzlich in dieser Sandbox. Acht ältere Empfänger-Payouts sind per API als `automatic=true`, `paid` und `livemode=false` belegt.
- **Mitarbeiter-Dashboard.** Die standardmäßig aktivierte lokale Simulation erzeugt einen Gratilo-Datensatz mit `po_sim…`. Daher erscheint eine dort simulierte Auszahlung ausschließlich in Gratilo. Stripe-Payouts der Mitarbeiter stehen beim jeweiligen Connected Account. Die Payout-Ansicht der Plattform enthält ihre eigenen Auszahlungen.

## Drei Stationen des Geldflusses

| Begriff | Pool | Einzelzahlung |
| --- | --- | --- |
| Payment | Stripe Checkout, Payment Intent und Charge auf der Plattform. Gratilo speichert die Preis- und Verteilungsbasis zum Checkout. | Stripe Checkout, Payment Intent und Charge im gespeicherten Connected-Account-Kontext. Die Application Fee wird der Plattform zugeordnet. |
| Transfer | Der Pool-Worker erzeugt für jede freigegebene Zuteilung einen `tr_…` mit `source_transaction` und `transfer_group`. Der Empfänger erhält eine eigene Guthabenbuchung. | Der Direct Charge schreibt das Guthaben dem Connected Account direkt gut. |
| Payout | Stripe bündelt auszahlbares Guthaben des Empfängers nach dessen Plan und erzeugt ein `po_…`. | Dieselben Auszahlungsvoraussetzungen und derselbe kontobezogene Plan gelten. |

[Separate Charges and Transfers](https://docs.stripe.com/connect/separate-charges-and-transfers) · [Direct Charges](https://docs.stripe.com/connect/direct-charges)

### Verarbeitung in Gratilo

- [StripeCheckoutService](../backend/payment-service/src/main/java/com/gratilo/payment/service/StripeCheckoutService.java) erstellt beide Checkouts und bestätigt über einen kontogebundenen Statusabgleich eine bezahlte Session. Der Pool friert Empfänger, Beträge und Gruppenreferenz beim Checkout ein. Einzelzahlungen übergeben den Connected Account und die konfigurierte Application Fee. [StripeFeeAuditService](../backend/payment-service/src/main/java/com/gratilo/payment/service/StripeFeeAuditService.java) liest Charge und Zahlungsbuchung im jeweiligen Kontokontext und die Application Fee auf der Plattform.
- [StripeWebhookController](../backend/payment-service/src/main/java/com/gratilo/payment/controller/StripeWebhookController.java) und [StripeDirectWebhookController](../backend/payment-service/src/main/java/com/gratilo/payment/controller/StripeDirectWebhookController.java) prüfen getrennte Signaturen und den Testmodus. `checkout.session.completed` bucht nur bei `payment_status=paid`. `checkout.session.async_payment_succeeded` und `payment_intent.succeeded` bestätigen erfolgreiche Folgezustellungen. Ohne bestätigte Buchung wartet der Pool-Worker.
- [PoolTransferSettlementService](../backend/payment-service/src/main/java/com/gratilo/payment/service/PoolTransferSettlementService.java) läuft mit `features.tip-pools-enabled` im 60-Sekunden-Takt. Er prüft bestätigte Quelle, eingefrorene und freigegebene Zuteilungen sowie empfangsbereite Accounts. Er gleicht vorhandene Stripe-Transfers ab, sperrt Buchung und Zuteilung und verwendet einen stabilen Idempotenzschlüssel je Zuteilung. Nach dem begrenzten POST-Wiederholungsfenster bleibt eine unklare Zuordnung zur Klärung stehen. `TRANSFERRED` beschreibt die Guthabenübertragung. Alte Zuteilungen mit `settlement_approved=false` bleiben mit `APPROVAL_REQUIRED` stehen. Der Worker lief erfolgreich mit der isolierten Testdatenbank. Der laufende Worker der Deployment-Umgebung war in diesem Zugriff nicht prüfbar.
- [StripeConnectService](../backend/payment-service/src/main/java/com/gratilo/payment/service/StripeConnectService.java), [StripeAccountStatus](../backend/payment-service/src/main/java/com/gratilo/payment/service/StripeAccountStatus.java) und [StripeConnectWebhookController](../backend/payment-service/src/main/java/com/gratilo/payment/controller/StripeConnectWebhookController.java) erstellen und prüfen Express-Accounts. Sie unterscheiden Payments, Transfers und Payout-Berechtigung. Für eine der Account-Erstellungsvarianten verwendet der Code die separate Preview-Version `2026-06-24.preview`.
- [PayoutManager](../frontend/src/PayoutManager.tsx) zeigt eigene Gratilo-Auszahlungsaufträge, lokales Simulationsguthaben und verfügbares oder ausstehendes Stripe-Guthaben. Automatische Stripe-Payouts und Plattform-Payouts lassen sich im Stripe-Dashboard einsehen. Auch das vorhandene Admin- und Audit-Datenmodell weist Payment und Transfer aus, es führt keine Zuordnung eines einzelnen Trinkgelds zur automatischen Bankauszahlung. Die bestehende [Migration 015](../backend/payment-service/src/main/resources/db/changelog/payment_mgmt/015_manual_payout_safety.sql) schützt Gratilo-Aufträge zusätzlich durch eindeutige Provider-Referenzen und höchstens einen aktiven Auftrag. Die Änderungen dieses Berichts benötigen keine Migration.
- [StripePayoutService](../backend/payment-service/src/main/java/com/gratilo/payment/service/StripePayoutService.java) erstellt standardmäßig lokale Simulationen. Ein von Gratilo ausgelöster Stripe-Test-Payout setzt einen expliziten Auftrag, deaktivierte lokale Simulation, API-Freigabe für manuelle Auszahlungen, einen für Standardauszahlungen geeigneten EUR-Empfänger und nachgewiesenen manuellen Plan voraus. Der optionale [StripePayoutSetupWorker](../backend/payment-service/src/main/java/com/gratilo/payment/service/StripePayoutSetupWorker.java) läuft mit gesondertem Opt-in. Bei unklarem POST-Ergebnis fragt Gratilo Stripe ab. Ein erneuter POST könnte eine zweite Auszahlung erzeugen und ist in diesem Pfad gesperrt.

### Stripe-Events

| Event | Verarbeitung |
| --- | --- |
| `payout.created`, `payout.updated` | Mögliche frühe Statusänderungen eines bestehenden Gratilo-Auftrags |
| `payout.paid`, `payout.failed`, `payout.canceled` | Jeweils signierter Connected-Account-Status für einen zugeordneten Gratilo-Auftrag. Ein späteres `payout.failed` kann auch nach `payout.paid` ankommen. |
| `balance_settings.updated`, `account.external_account.{created,updated,deleted}` | Erneute Prüfung der optionalen manuellen Einrichtung im Connected-Kontext |
| `account.updated` | Bestehender Abgleich der Connect-Berechtigungen |
| `balance.available` | Der Pool-Worker liest den Status direkt bei Stripe. Für automatische Payouts steuert Stripe den Auszahlungsplan. |
| Plattform-Payout-Events | Die Gratilo-Plattform hat keinen appseitigen Auszahlungsauftrag. Der Plattformstatus bleibt in Stripe. |

[Stripe-Ereignistypen](https://docs.stripe.com/api/events/types) · [Connect-Webhooks](https://docs.stripe.com/connect/webhooks)

## Sandbox-Bestandsaufnahme

Die vorhandene lokale Konfiguration lieferte Zugriff auf die unten genannte Sandbox. Schlüsseltyp, Plattform-ID und `livemode=false` wurden vor Schreibtests geprüft. Die Inventur vor den Tests ergab 75 Plattform-Balance-Transactions, 32 Checkout-Sessions, 30 gelistete Charges, 29 Transfers und 17 Application Fees. Plattform-`balance_settings` und Payout-Liste waren erreichbar. Das Plattformbankkonto war per API nicht einsehbar. Für die separate Deployment-Sandbox `acct_1UQNErJnhDgmiMAM` war die AWS-SSO-Sitzung abgelaufen. Dort sind aktueller Workerstatus und Payouts nicht belegt. Der [frühere Deployment-Test](stripe-sandbox-single-pool-test.md#pooltest-zwei-sct-empfänger) dokumentiert zwei erfolgreiche Pool-Transfers an Empfänger mit damaligem manuellem Plan.

| Konto | Charges / Payouts | Capabilities Card / Transfers | EUR verfügbar / ausstehend vor Tests | Externe Konten und Befund |
| --- | --- | --- | --- | --- |
| `acct_1UFbtVCMj7Vzvm34` | false / false | nicht im Kontoobjekt vorhanden | 17.466,47 / 0,00 | Bank GET 403, `req_a1FTzYHivgUqSV`. Auszahlung deaktiviert, nominal täglich, `req_QSIkKdrCnPwLWF`. |
| `acct_1UeyYlCMj7ljPTXn` | true / true | active / active | 0,00 / 0,00 | EUR Standard, täglich, keine offenen Pflichtfelder |
| `acct_1UJFmGCMj7iPU5TL`, `acct_1UJ6vuCMj7Y2Rkwp`, `acct_1UGFJ3CMj7h4O2B5`, `acct_1UFwWXCMj7gOB1JT`, `acct_1UFvukCMj7F16Uaj` | true / true | active / active | je 0,00 / 0,00 | Je EUR Standard und Instant laut `available_payout_methods`, täglich, keine offenen Pflichtfelder |
| `acct_1UeyV7CMj7YtOdU8`, `acct_1UeyTvCMj7gHiWNX`, `acct_1UFtAMCMj7As9BMp` | false / false | inactive / inactive | je 0,00 / 0,00 | Kein Bankkonto, `requirements.past_due` einschließlich `external_account` und Personen-/Geschäftsdaten |
| `acct_1UFtALCMj7yzAyy1` | false / false | inactive / inactive | 93,60 / 0,00 | Verifiziertes USD-Bankkonto für Standard, kein EUR-Auszahlungskonto in der Liste. `requirements.past_due` für Personen-/Geschäftsdaten, Auszahlung deaktiviert, `req_RA66YhaWVqPUaZ`. |

Alle gelesenen Pläne standen nominell auf `daily` mit `delay_days=7`. Offene Anforderungen können die Auszahlung unabhängig vom Plan blockieren. Die Stripe-Testkarte mit sofort verfügbarer Buchung lieferte in A und B einen früheren konkreten `available_on`-Zeitpunkt als die allgemeine Kontoeinstellung erwarten ließe. Für EUR-Standardauszahlungen in Deutschland nennt Stripe mindestens 1,00 EUR. Die Sandbox hatte vor dem Test acht bezahlte und null fehlgeschlagene Payouts. [Stripe-Testkarten und verfügbares Guthaben](https://docs.stripe.com/testing#available-balance) · [Stripe-Mindestbeträge](https://docs.stripe.com/payouts#minimum-payout-amounts)

Älterer Direct Charge über 100,00 EUR auf `acct_1UFtALCMj7yzAyy1` mit Payment Intent `pi_3UFtAVCMj7yzAyy108umRgYi`, Charge `ch_3UFtAVCMj7yzAyy10jynOhry`, Application Fee `fee_1UFtAYCMj7Vzvm34YJ99e97X` über 3,00 EUR und Guthabenbuchung `txn_3UFtAVCMj7yzAyy10QV3Ed5s`. Der Kontostatus und das verfügbare Guthaben lokalisieren den Stopp nach der Gutschrift, vor einer Bankauszahlung. Historisch war keine Checkout Session zu dieser Charge gelistet.

## Kontrollierte Sandbox-Tests

Die neuen Checkout Sessions verwendeten die aktuelle Gratilo-Preis- und Connect-Struktur mit Stripe-Testkarte und fix auf Karte begrenzter Zahlungsmethode. Die Gratilo-Zahlungsstatusprüfung, der Worker und PostgreSQL liefen lokal. Nach dem Leseabgleich wurden drei einmalige Stripe-Testauszahlungen mit eigenen Idempotenzschlüsseln über die jeweils zuvor verfügbaren Testbeträge erstellt. Alle Empfängerkonten hatten vor den Tests kein verfügbares Guthaben. Kein bestehender Auszahlungsplan wurde verändert.

| Test | API-Nachweis | Auszahlungsstatus |
| --- | --- | --- |
| A · Direct Charge | 5,42 EUR bezahlt, Application Fee 0,70 EUR, Empfängerguthaben 4,72 EUR verfügbar. | Ein Standard-Payout erstellt und als `paid` bestätigt. Täglicher Plan weiterhin aktiv. |
| B · Pool | 5,42 EUR bezahlt, Plattform-Charge netto 5,09 EUR nach 0,33 EUR Stripe-Gebühr. Zwei Quellen-Transfers über je 2,36 EUR, beide Empfängergutschriften verfügbar. Bei der Plattform verbleiben aus diesem Pool-Payment 0,37 EUR nach Quellen-Transfers und Stripe-Gebühr. | Zwei Standard-Payouts erstellt und als `paid` bestätigt. Beide täglichen Pläne weiterhin aktiv. |
| C · Plattform | A Application Fee 0,70 EUR als eigene Plattformbuchung verfügbar. Pool siehe B. | Blockiert durch `payouts_enabled=false` und `payments.payouts.status=disabled`. Bank-GET unberechtigt. Kein POST. |
| D · Failed Payout | Lokale signierte `payout.failed`-Regressionsfixture und Statusprüfung mit `account_closed`. Stripe lehnte die isolierte API-Einrichtung für den dokumentierten AT-Testbankfall ab. Siehe API-Ergebnisse unten. | Kein neuer fehlgeschlagener Payout in Stripe belegt. |
| E · Doppelte Zustellung | Dieselbe signierte Checkout-Nachricht wurde zweimal an die lokale Gratilo-Plattform-Route übergeben. Beide Zustellungen ergaben HTTP 200. Der Worker lief erneut und Stripe listete weiterhin exakt die zwei erwarteten Transfers zur Gruppe. Backend-Tests prüften doppelte und verspätete signierte Zustellungen, Antwortzeit vs. Eventzeit, konkurrierende Auszahlungsanfragen und unklare POST-Ergebnisse. | Kein doppelter Transfer und kein zweiter POST in den geprüften Pfaden. |

### Objekt-Nachweise für A und B

| Objekt | A · Connected Account | B · Plattform |
| --- | --- | --- |
| Konto | `acct_1UeyYlCMj7ljPTXn` | `acct_1UFbtVCMj7Vzvm34` |
| Checkout Session | `cs_test_a0Ijhx7SRGODbDrG92EKtbN38aMdOw0GaaO32jgncAE2qf3Xo4DV7aGYMB` | `cs_test_a09MMkXC3JTLEtB52KxBxigykKp1jn0kJEWRabdBMnHEyp57n7QCTAzWzu` |
| Payment Intent | `pi_3UfgDLCMj7ljPTXn0GhamyLa` | `pi_3UfgDPCMj7Vzvm341lrFUKCH` |
| Charge | `ch_3UfgDLCMj7ljPTXn0Btr9xRK` | `ch_3UfgDPCMj7Vzvm341TP6j3l8` |
| Charge-Buchung | `txn_3UfgDLCMj7ljPTXn05C95HWj` | `txn_3UfgDPCMj7Vzvm341iRXqnRZ` |
| Plattformgebühr | `fee_1UfgDOCMj7ljPTXnDLSRA3Bs` · `txn_1UfgDRCMj7Vzvm347y3p4CrY` | Differenz aus Charge und Transfers. Kein separates Application-Fee-Objekt |
| Stripe-Requests | Checkout `req_kdrmfu2DDD09wO` · Guthaben `req_6zQLRDZlohUenh` · Payout-Liste vor Anforderung `req_P8eM8RLCv9nzwt` | Checkout `req_IWOW0lvWGSbKe8` · Charge-Buchung `req_H443ENqDGBKtKL` · Transfer-Liste nach Wiederholung `req_8ZuHdyPdZHLvZO` |

| Pool-Empfänger | Stripe-Transfer und Plattformbuchung | Connected Payment und Guthabenbuchung |
| --- | --- | --- |
| `acct_1UJFmGCMj7iPU5TL` | `tr_3UfgDPCMj7Vzvm341sol1FWo` · `txn_3UfgDPCMj7Vzvm341XolYbfb` | `py_1UfgIMCMj7iPU5TLIFAVTXla` · `txn_1UfgIMCMj7iPU5TLG9REssT3` · 2,36 EUR verfügbar |
| `acct_1UGFJ3CMj7h4O2B5` | `tr_3UfgDPCMj7Vzvm341YXtENes` · `txn_3UfgDPCMj7Vzvm3418TZioeF` | `py_1UfgIOCMj7h4O2B59DNvKb04` · `txn_1UfgIPCMj7h4O2B51whmeyAH` · 2,36 EUR verfügbar |

Der Direct Payment Intent war im Connected-Kontext abrufbar (HTTP 200, `req_9d8Fc61j0IRhZe`). Im Plattformkontext lieferte derselbe Intent HTTP 404 `resource_missing` (`req_2tLcl9JaGtlMJU`). Vor den gezielten Testauszahlungen waren die Payout-Listen auf der Plattform leer (`req_dsMR72OdjcbWnT`) und enthielten bei den Testempfängern ausschließlich ältere Auszahlungen oder keine Einträge (`req_P8eM8RLCv9nzwt`, `req_RsX8kaRPQ6cwjE`, `req_IcBM44Pu3fejxh`).

### Neu erstellte Sandbox-Payouts

Alle drei Requests erhielten HTTP 200. Die Auszahlungen sind manuell veranlasste Stripe-Sandbox-Objekte mit `livemode=false` und `automatic=false`. Der Stripe-Saldo ist kontobezogen. Bei diesen gezielt angestoßenen Auszahlungen ist kein Stripe-Abgleich auf individuelle Eingangsbuchungen verfügbar. Der Bezug zu den Testvorgängen folgt aus den protokollierten Konten, den vorherigen Nullsalden und den identischen verfügbaren Beträgen vor dem POST. Stripe meldete beim POST zunächst `pending`. Der nachfolgende GET bestätigte bei allen drei `paid`, verfügbares Restguthaben von 0 EUR und weiterhin `daily` als Plan. [Stripe-Payout-Erstellung](https://docs.stripe.com/api/payouts/create) · [Stripe-Auszahlungsabgleich](https://docs.stripe.com/reports/payout-reconciliation)

| Test | Connected Account | Stripe-Payout | Payout-Buchung | Betrag | Zustand / erwartete Ankunft | Request |
| --- | --- | --- | --- | --- | --- | --- |
| A | `acct_1UeyYlCMj7ljPTXn` | `po_1UfgOOCMj7ljPTXnG56HbpG9` | `txn_1UfgOOCMj7ljPTXnB1qHQAVD` | 4,72 EUR | `paid` / 26.11.2026 | POST `req_oUIkGhEJZ23ttp`, GET `req_xIp3SfXlEnlcpA` |
| B1 | `acct_1UJFmGCMj7iPU5TL` | `po_1UfgOOCMj7iPU5TLlIvypmN1` | `txn_1UfgOOCMj7iPU5TLJKlfk9aM` | 2,36 EUR | `paid` / 26.11.2026 | POST `req_yCLAOq1gvNvlky`, GET `req_qGHP1Kt55XD5ZU` |
| B2 | `acct_1UGFJ3CMj7h4O2B5` | `po_1UfgOOCMj7h4O2B5RH3ZoNvi` | `txn_1UfgOOCMj7h4O2B5nlVjOZNo` | 2,36 EUR | `paid` / 26.11.2026 | POST `req_FDm8nbYsD0qPmY`, GET `req_wcfILIH29gVuqJ` |

### Historischer Dashboard-Nachweis

Stripe zahlte für `acct_1UJFmGCMj7iPU5TL` automatisch 60,40 EUR aus. Der Sandbox-Payout `po_1ULuP4CMj7iPU5TLsYR8zIDZ` war `paid` mit erwartetem Ankunftsdatum 02.10.2026 und Buchung `txn_1ULuP4CMj7iPU5TLRpFdhTFb`. Stripe ordnete diesem Payout zwei ältere Empfängergutschriften über 50,00 EUR und 10,40 EUR zu. Das bestätigt die Bündelung anhand der API `GET /v1/balance_transactions?payout=…` im Empfängerkontext, HTTP 200, `req_QyrcmsTRTcU7hO`. Die historischen Gutschriften gehören zu früheren Stripe-Zahlungen und wurden keinem der neuen Gratilo-Testfälle zugerechnet. [Stripe-Auszahlungsabgleich](https://docs.stripe.com/reports/payout-reconciliation)

### Isolierter negativer Test

Stripe dokumentiert eine österreichische Testbankverbindung für `account_closed`. Die API-Onboarding-Varianten für ein isoliertes Konto wurden mit HTTP 400 abgelehnt. Accounts v1 mit Legacy-Custom wurde wegen der Verlustverantwortung abgelehnt (`req_OaBGBllgNzfGCC`). Accounts v2 mit Plattform-Verlustverantwortung lieferte `account_creation_losses_collector_unavailable` (`req_v2hYmk8ebLP5WRGhX`). Für einen Stripe-gesteuerten Account wurde stellvertretende AGB-Annahme abgelehnt (`tos_acceptance_on_behalf_not_allowed`, `req_v2ejIO5pRxoUW4wZD`). Mit Stripe als Verantwortlichem für die Verluste und ohne stellvertretende AGB-Annahme wurde das isolierte Sandbox-Konto `acct_1UfgJMCMj7DnM4Xy` erstellt (HTTP 200, `req_v2HSkmeqhKZICLLkJ`). Das Konto meldet weiterhin `charges_enabled=false` und `payouts_enabled=false` (`req_Ws5B2vW9CPY2nc`). Ein Onboarding-Link wurde erstellt (`req_3lXtpHK7VT28ss`). Der automatisierte Aufruf des Stripe-geführten Onboardings konnte in dieser Sitzung nicht abgeschlossen werden. Der Versuch, die Fehler-Testbank direkt am isolierten Konto anzulegen, lieferte außerdem HTTP 403 `oauth_not_supported` (`req_zme7jhT5IXxOo4`). Für die externe Wiederholung muss das Stripe-geführte Onboarding dieses Kontos abgeschlossen und die dokumentierte Fehlerbank hinterlegt werden. Bereits verwendete Empfängerkonten bleiben für diesen Fehlerfall unverändert. [Stripe-Testauszahlungen](https://docs.stripe.com/payouts#test-payouts) · [Connect-Testverifizierung](https://docs.stripe.com/connect/testing-verification?accounts-namespace=v2) · [Stripe Managed Risk](https://docs.stripe.com/connect/risk-management/connected-accounts-with-managed-risk)

## Korrekturen

| Dateien | Korrektur |
| --- | --- |
| `PayoutState.java`, `PayoutStore.java` | Stripe-Eventzeit wird getrennt vom lokalen Zeitstempel einer API-Antwort gewertet. Verspätete bestätigte Fehler können den Status korrigieren, auch innerhalb derselben Sekunde. Doppelte Terminal-Events behalten ihren Abschlusszeitpunkt. Status wird an Konto, Betrag, Währung, Zielbank und vorhandene Auftragsmetadaten gebunden. Automatische Stripe-Payouts können einen manuellen Gratilo-Auftrag nicht übernehmen. |
| `StripeBalanceService.java`, `StripePayoutService.java` | Guthabenprüfung und Auszahlungs-POST verwenden dasselbe bereits ermittelte und gespeicherte Stripe-Konto. Beim Abgleich werden die gespeicherten Referenzen weiter geprüft. |
| `StripeWebhookController.java` | Payout-Events laufen ausschließlich über den vorgesehenen Connect-Auftragspfad. Plattform- und unzugeordnete Events lösen keine generische Zahlungsbuchung aus. |
| `docker-compose/stripe_test.py`, `compose.stripe-test.yml`, `docker-compose.infra.yml` | Lokale CLI-Weiterleitung um fehlende Payout-Lifecycle- und Einrichtungsereignisse ergänzt. Bestehende Webhook-Destinationen in Stripe wurden nicht geändert. |
| `PayoutWebhookTest.java`, `ManualPayoutTest.java` | Regressionen für verspätete und doppelte Events, Failure-Code, Zustellung am falschen Endpunkt und abweichende Kontodaten |

Gebühren, Provisionen, Zahlungsmodelle und Auszahlungsintervalle bleiben wie konfiguriert. Die nachgewiesenen Anwendungsfehler betrafen Status und Zuordnung bereits angeforderter Auszahlungen. Die deaktivierte Plattformfreigabe und das unvollständige Onboarding erklären die per API belegten externen Stopps.

## Automatisierte Verifikation

- Payment-Service mit Gradle und Java 21, einschließlich PostgreSQL-Integrationstests sowie Assemble. 336 Tests erfolgreich.
- Mitarbeiter-Auszahlungsansicht mit Vitest. 20 Tests erfolgreich.
- Stripe-Setup- und Compose-Regressionen mit Python unittest. 15 Tests erfolgreich.

## Betrieb und offene Schritte

1. Im Stripe-Dashboard die Sandbox anhand der Plattform-ID auswählen. Für Plattformauszahlungen **Guthaben → Auszahlungen** öffnen. Für Mitarbeiter-Auszahlungen den oben dokumentierten Connect-Pfad und die jeweilige Connected-Account-ID verwenden.
2. Beim gesperrten Connected Account die fälligen Informationen im Stripe-Onboarding aktualisieren und eine passende EUR-Auszahlungsmethode prüfen. Bei der Plattform die Testkonto-Einrichtung und den fehlenden konkreten Freigabegrund im Dashboard prüfen. Danach `payouts_enabled=true`, aktivierten Plan, EUR-Standardziel und Guthaben vor dem Test bestätigen.
3. Den Status der drei neu angelegten Test-Payouts anhand ihrer IDs prüfen. Bei künftigen automatischen Payouts lässt sich der gebündelte Inhalt mit `GET /v1/balance_transactions?payout=…` abgleichen. `available_on` steuert die Guthabenverfügbarkeit, der Plan die Auszahlungserstellung, `arrival_date` die erwartete Ankunft. Ein manueller Plan erfordert einen ausdrücklichen Stripe-Auszahlungsauftrag.
4. Vor einer Auszahlung auf Plattformebene den tatsächlich freien Anteil ermitteln. Offene Pool-Verbindlichkeiten anhand gespeicherter freigegebener Zuteilungen und vorhandener Transfers abstimmen. `available` und `connect_reserved` allein bilden Gratilos noch nicht transferierte Ansprüche nicht vollständig ab. Für neue quellgebundene Pool-Transfers prüft der bestehende Worker dieselbe Charge und ihre Gesamtsumme. Stripe dokumentiert, dass automatische Plattformauszahlungen Transfers ohne `source_transaction` beeinträchtigen können. Ein operativ festgelegtes Mindestguthaben beziehungsweise eine Freigaberegel für Plattformauszahlungen ist eine gesonderte Entscheidung. [Stripe zu Ledger und Quellbindung](https://docs.stripe.com/connect/separate-charges-and-transfers) · [Stripe zu Mindestguthaben](https://docs.stripe.com/payouts/minimum-balances-for-automatic-payouts)
5. In der Deployment-Sandbox nach erneuerter Sitzung (`aws sso login --profile gratilo`) Stripe-Endpunkte, Worker-Metriken und offene Zuteilungen prüfen. Bei tatsächlich aktivierten Gratilo-Auszahlungsaufträgen die dokumentierten Payout- und Einrichtungsevents an der Connected-Destination abonnieren. Standardmäßig genügt für Bankauszahlungen der automatische Stripe-Plan.
6. Die Sandbox simuliert Stripe-Objekte und Bankstatus ohne Geldbewegung. Im Live-Modus hängen Termine zusätzlich von Land, Zahlungsmethode, Bank, Wochenenden, Feiertagen, Verifizierung und Auszahlungsplan ab. Das hier untersuchte Gratilo-Deployment und der API-Client sind auf Testbetrieb eingeschränkt. Die Stripe-Testnachweise erlauben keine Aussage über die Live-Freigabe. [Stripe-Auszahlungspläne](https://docs.stripe.com/connect/manage-payout-schedule) · [Stripe Connect Payouts](https://docs.stripe.com/connect/manage-connected-account-payouts) · [Stripe Payout API](https://docs.stripe.com/api/payouts/create)
