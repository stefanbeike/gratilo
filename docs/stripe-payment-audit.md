# Stripe-Zahlungs-Audit für neue Trinkgelder

Stand: 29. Oktober 2026.

## Eintrittsgrenze und unveränderte Zahlungslogik

Das neue Audit beginnt ausschließlich im **Neuanlagezweig** des bestehenden
Stripe-Checkouts: nach dem Anlegen einer neuen internen Stripe-TIP-Transaktion,
vor dem Erstellen ihrer Checkout-Session. Einzelzahlungen werden als `SINGLE`,
Pool-/Teamzahlungen als `POOL` aufgenommen. Die Aufnahme erfolgt in derselben
Datenbanktransaktion wie die neue interne Zahlung.

Ein bereits vorhandener Zahlungsversuch wird beim Wiederaufnehmen **nicht** neu
aufgenommen, auch wenn seine Stripe-Session erst später vervollständigt wird.
Die Aufnahmegrenze ist die neue interne Zahlung, keine nachträgliche Suche nach
Stripe-Erstellungsdaten. Bereits vorhandene Session-/PaymentIntent-/Charge-
Referenzen werden an der Aufnahmegrenze abgewiesen.

Es gibt keinen Import, Backfill, historischen Transaktionsscan oder Admin-Endpunkt
zur Aufnahme alter Zahlungen. Webhooks, Transferbeobachtung und Reconciliation
können ausschließlich bereits vorhandene Audit-Zeilen aktualisieren. Fehlt die
Zeile, führen sie keine Stripe-Suche oder Audit-Neuanlage aus.

Die bisherigen Tabellen `payment_fee_audits`, `payment_fee_events`, Transaktionen,
Pricing-Snapshots und Pool-Allokationen werden nicht übernommen oder umgeschrieben.
Der neue Audit ergänzt die bestehende Gebührenauswertung. Betrag, Gebührenmodell,
Empfängeranteile, Transferfreigaben, Refund- und Reversal-Entscheidungen bleiben
in den bisherigen Prozessen. Der Audit selbst hat ausschließlich Stripe-GETs;
er erstellt keine Zahlung, keinen Transfer, Refund, Reversal oder Payout.

## Tabellen und Liquibase

Die neue Liquibase-Datei
`backend/payment-service/src/main/resources/db/changelog/payment_mgmt/019_stripe_payment_audit.sql`
ist im vorhandenen Master-Changelog eingebunden. Sie enthält ausschließlich
DDL für die neuen Tabellen, Indizes, Constraints und den Ereignisschutz. Es gibt
keine Datenübernahme und keine Änderung historischer Zahlungszeilen.

Alle Tabellen liegen im Schema `payment_mgmt`:

| Tabelle | Inhalt und Schlüssel |
|---|---|
| `stripe_payment_audit` | Eine Zeile pro neuer interner `payment_id` (UUID, FK auf `transactions`), `SINGLE`/`POOL`, `livemode`, verifiziertes Plattformkonto, eingefrorener Charge-Kontokontext, Session-/PI-/Charge-/Zahlungs-Balance-ID, Währung, vertraglicher erwarteter Gesamt-/Empfängerbetrag, Status, aktueller gefilterter Finanz-Snapshot, externe Kostennachweise, Revision, Prüftermine und feste Fehlercodes. |
| `stripe_payment_transfer_audit` | Beliebig viele Transfers pro Zahlung; PK `(payment_id, transfer_id)`. Enthält Modus, Zielkonto, Source-Charge, Betrag, rückübertragenen Betrag, Währung, Plattform-Balance-ID, Empfängerzahlungs-/Balance-ID, Finanzdaten einschließlich Reversals und `observed_revision`. |
| `stripe_payment_fee_audit` | Einzelne belegte Gebührenpositionen. PK `(payment_id, cost_key)`. Der deterministische Schlüssel enthält Kontokontext, Balance-Transaction-ID und Positionsindex; zusätzlich sind Account, Quelle, Typ, Betrag, Währung, gefilterte Detailfelder und `observed_revision` gespeichert. Geprüfte Plattform-Kostenberichte erhalten einen getrennten, stabilen Schlüssel. |
| `stripe_payment_audit_event` | Append-only-Ereignisse mit fortlaufender ID, Payment-ID, eindeutigem Ereignisschlüssel je Zahlung, Ereignistyp, gefilterten Finanzfeldern und Erfassungszeit. |

Geldbeträge sind `BIGINT` in Minor Units. Es werden keine gerundeten EUR-Floats
für Finanzberechnungen gespeichert. Die erwarteten Checkout-/Empfängerbeträge
sind **Vertrags-/Sollwerte**, keine Schätzung von Stripe-Kosten.

Suchindizes bestehen für Modus, Session, PaymentIntent, Charge und Transfer.
Die Due-Abfrage ist über `(livemode, next_check_at)` indiziert. Zusammengesetzte
Fremdschlüssel verhindern Transfer-/Gebührenzeilen mit einem anderen Modus als
ihre übergeordnete Zahlung.

Die Ereignistabelle hat einen eindeutigen Schlüssel `(payment_id, event_key)`.
Datenbanktrigger verbieten `UPDATE`, `DELETE` und `TRUNCATE`. Finanzhistorie wird
nicht durch Aktualisieren eines Ereignisses ersetzt. Aktuelle Root-/Transfer-/
Gebühren-Snapshots dürfen sich ändern; ihre vorherigen verifizierten Zustände
bleiben als Ereignisse erhalten. Nicht erneut lesbare Detailzeilen sind durch
`observed_revision` von frisch verifizierten Zeilen unterscheidbar. Bei einem
vollständigen Snapshot werden überholte Gebührenpositionen aus der aktuellen
Gebührentabelle entfernt; die früheren Ereignisse bleiben erhalten.

## Integration in bestehende Prozesse

### Checkout

`StripeCheckoutService` nimmt genau in den beiden neuen Transaktionszweigen auf:

1. Neue Einzelzahlung: `SINGLE`; der Direct-Charge-Kontokontext wird aus den
   eingefrorenen Zahlungsmetadaten übernommen.
2. Neue Poolzahlung: `POOL`; die Charge verbleibt im Plattformkontext.
3. Nach erfolgreicher Session-Erstellung wird deren tatsächlich zurückgegebene
   Stripe-ID an die bereits aufgenommene Audit-Zeile gebunden.

Die Plattformkonto-ID bleibt zunächst unbekannt und wird bei der ersten
Reconciliation durch einen echten `GET /v1/account` belegt. `GET /v1/balance`
prüft den Modus. Eine einmal gebundene Plattform-ID darf durch einen anderen
Schlüssel/Kontokontext nicht ersetzt werden. Aus unbekannten Daten wird keine
`acct_...`-ID konstruiert.

### Pool-Verteilung

Der bestehende Settlement-Prozess meldet einen bereits geprüften und gemerkten
Transfer an den Audit. Er stößt dadurch lediglich die nächste Audit-Prüfung an.
Der Audit berechnet keine neue Verteilung und löst keinen weiteren Transfer aus.

Die Reconciliation liest die tatsächliche Transfergruppe des neuen PaymentIntents
und die bereits gespeicherten Transfer-IDs seiner eingefrorenen Allokationen.
Sämtliche Seiten werden gelesen; es gibt keine feste Beschränkung auf zwei
Empfänger oder eine Stripe-Seite. Ein Test umfasst 125 Transfers auf zwei Seiten.

Eine gemeinsame Transfergruppe allein genügt nicht: `source_transaction` muss
exakt auf die verifizierte Charge zeigen. Transfer-Ziel, Beträge und Reversals
werden separat belegt. Eine Abweichung von den eingefrorenen Zielkonten oder der
noch geschuldeten Verteilung wird sichtbar ausgewiesen, ohne Anteile zu ändern.

### Signierte Webhooks

Der Hook sitzt im bestehenden gemeinsamen Prozessor der Plattform- und
Direct-Webhook-Endpunkte **hinter** Signatur- und Testmodusprüfung. Er verarbeitet
relevante `checkout.session.*`, `payment_intent.*`, `charge.*`, `refund.*`,
`transfer.*` und `application_fee.*`-Ereignisse.

Die Zuordnung sucht ausschließlich in den neuen Audit-Tabellen nach bekannten
Session-/PI-/Charge-/Transfer-IDs oder einer passenden internen Payment-ID.
Modus und Account-Kontext müssen übereinstimmen. Für Transfers wird die
Source-Charge beziehungsweise der bereits bekannte Parent-Transfer geprüft;
für Application Fees und Refunds werden Charge-/Account-Beziehungen geprüft.
Provider-Metadaten können eine Prüfung anstoßen, ersetzen aber keine eingefrorene
PI-/Charge-Referenz. Die einzige Session-Bindung aus einem frühen signierten
Session-Ereignis verlangt die passende neue interne Payment-ID und einen
konsistenten Gesamtbetrag; der spätere GET validiert die Beziehung erneut.

Der Ereignisschlüssel wird aus Stripe-Event-ID, Modus und Account-Kontext gebildet.
Doppelte Ereignisse erzeugen keine zweite Zeile. Ein Ereignis weckt die Prüfung;
der aktuelle Finanzzustand wird über GET gelesen. Ein verspätetes Ereignis kann
keinen älteren Geldbetrag direkt in den aktuellen Snapshot zurückschreiben.
Die tatsächliche Stripe-Event-ID und der Kontext bleiben in den gefilterten
Ereignisdaten erhalten.

Die Funktion ändert keine Stripe-Webhook-Abonnements. Für zeitnahe Updates sollten
die genannten Ereignisse am passenden bestehenden Snapshot-Endpunkt abonniert
sein. Unabhängig davon prüft der Scheduler auch vollständige neue Audits erneut;
fehlende Abonnements führen nicht zu einem historischen Backfill.

## Reconciliation und Vollständigkeit

`StripePaymentAuditor` prüft alle 60 Sekunden höchstens 100 fällige Zeilen aus
**`stripe_payment_audit`**, sortiert nach Fälligkeit. Die interne Zahlungs- oder
Stripe-Payment-Liste wird nicht nach noch nicht aufgenommenen Zahlungen durchsucht.
Unvollständige/fehlgeschlagene Audits werden nach einer Minute erneut fällig;
vollständige Audits nach einer Stunde, damit spätere Änderungen auch ohne Webhook
auffallen. Ein gültiger neuer Webhook oder gemerkter Transfer macht die vorhandene
Zeile sofort fällig.

Je Zahlung hält der separate transaktionale Store während Lesen und Persistieren
eine PostgreSQL-Row-Lock. Bestehende Transaktions-/Allokationsänderungen werden vor der Audit-Sperre
geflusht; der Webhook-Hook läuft nach der bisherigen Zahlungszustandsverarbeitung.
Damit gilt dieselbe Sperrreihenfolge wie im Pool-Settlement (Zahlung/Allokation
vor Audit), statt einer gegenseitigen Wartesituation. Nach Sperrerwerb wird die
Audit-Zeile frisch geladen. Mehrere
Worker oder Webhooks serialisieren damit ihre Änderungen. Kinder-Upserts,
aktueller Snapshot und neue Ereignisse werden gemeinsam committed. Eine
Datenbankstörung rollt diesen Audit-Schritt zurück und verhindert keine spätere
Wiederholung.

Eine kanonische Darstellung und ein SHA256-Fingerprint verhindern neue
`RECONCILED`-Ereignisse, wenn sich dieselben Finanzdaten nicht geändert haben.
Prüfzeitpunkte gehören nicht zu diesem Fingerprint. Tatsächliche Änderungen
bekommen eine neue Revision und ein neues Ereignis. Bei temporären Lücken bleiben
frühere vollständige Nachweise ausdrücklich als `lastVerifiedData` erhalten und
werden nicht als frisch bestätigt ausgegeben.

### Nachweisbare Beziehungen

- Enrollment → Session: interne neue Payment-ID in `session.metadata.transaction_id`,
  Session-ID, Modus, Betrag und Währung.
- Session → PaymentIntent: `session.payment_intent`.
- PaymentIntent → Charge: `latest_charge` und Gegenprüfung
  `charge.payment_intent`. Die Charge-Versuchsliste wird vollständig nach genau
  diesem neuen PI gelesen; vorherige fehlgeschlagene Versuche bleiben sichtbar.
- Charge → Zahlungsbuchung: `charge.balance_transaction`, Gegenprüfung `source`.
- Charge → Transfer: `transfer.source_transaction`, zusätzliche Gruppe und
  bekannte lokale Transferreferenzen.
- Transfer → Plattformbuchung: `transfer.balance_transaction`, Gegenprüfung `source`.
- Transfer → Empfänger: `destination` und `destination_payment`; Lesen mit dem
  richtigen `Stripe-Account`-Header, anschließend dessen Balance-Referenz und `source`.
- Refund → Charge: `refund.charge`, gegebenenfalls `payment_intent`; tatsächliche
  Refund- und Failure-Balance-Transactions. Auch eine fehlgeschlagene Erstattung
  samt Rückgutschrift bleibt als Geldbewegung nachvollziehbar.
- Reversal → Transfer: `reversal.transfer`; tatsächliche Plattformbuchung und
  über `destination_payment_refund` belegte Empfänger-Rückbuchung.
- Application Fee → Charge/Account: `application_fee.charge` und `.account`,
  anschließend ihre Plattform-Balance-Transaction und Application-Fee-Refunds
  einschließlich einer belegten Rückgutschrift auf dem Connected Account. Fehlt
  diese Gegenbuchung, bleibt eine ausdrückliche Datenlücke.
- Dispute → Charge/PI: Charge-/PI-Felder und die tatsächlich referenzierten
  Dispute-Balance-Transactions. Persönliche Evidence-/Karten-/Kundendaten werden
  nicht gespeichert.

Bei paginierten Listen werden doppelte Objekt-/Cursor-IDs oder widersprüchliche
Beziehungen abgewiesen. Ein API-Fehler wird nicht als leere, vollständige Liste
interpretiert.

### Status und Lücken

| Audit-Status | Bedeutung |
|---|---|
| `PENDING` | Neu aufgenommen oder durch ein neues Ereignis zur Prüfung vorgemerkt. |
| `INCOMPLETE` | Verifizierte Teilkette vorhanden; `missingData` nennt fehlende/zurzeit nicht lesbare Daten oder eine offene Verteilung. |
| `COMPLETE` | Die erforderlichen Zahlungs-, Kosten- und Transferbeziehungen sind zum Prüfzeitpunkt vollständig verifiziert. `pending` bei einer Stripe-Balance bleibt als tatsächlicher Verfügbarkeitsstatus sichtbar. |
| `REVIEW_REQUIRED` | Provider-Modus, Betrag, Gebührenarithmetik oder Source-Beziehungen widersprechen den gebundenen Nachweisen. |
| `RETRY_REQUIRED` | Der Audit-Schritt konnte technisch nicht abgeschlossen werden; feste Fehlercodes statt roher Providerantworten. |

Beispiele für `missingData`: `CHECKOUT_SESSION_MISSING`, `PAYMENT_BALANCE_MISSING`,
`RECIPIENT_PAYMENT_UNAVAILABLE`, `POOL_DISTRIBUTION_PENDING`,
`REFUND_PENDING`, `REVERSALS_NOT_RECONCILED` und
`DIRECT_PLATFORM_PROCESSING_COST_EVIDENCE_MISSING`.
Die Admin-Antwort nennt Lücken auch vor der allerersten erfolgreichen Prüfung
(`AUDIT_NOT_RECONCILED`) beziehungsweise während einer erneuten Prüfung.

## Gebühren und von Gratilo getragene Kosten

Für jede gelesene Balance Transaction gilt in ihrem eigenen Account und ihrer
Währung die exakte Prüfung **`amount - fee = net`**. Jede `fee_details`-Position
wird mit Betrag, Währung, Typ, Application-Referenz und bereinigter Beschreibung
gespeichert. Die Positionssumme muss `fee` entsprechen. Fehlende Details sind
unbekannt; ein nachgewiesenes `fee=0` mit `fee_details=[]` ist dagegen ein realer
Nullwert. Weder aktuelle Tarifprozentsätze noch frühere Kostenschätzungen fließen
in diese Berechnung ein.

`application_fee` ist eine Provision/Geldbewegung, keine zusätzliche Stripe-
Bearbeitungsgebühr. Sie bleibt als Position sichtbar, wird aber nicht als
`stripeCost=true` gezählt. Gebühren von Empfängerkonten werden nicht als von der
Plattform bezahlte Kosten ausgegeben. Negative, belegte Kostenkorrekturen bleiben
mit ihrem Vorzeichen erhalten.

Der Snapshot unterscheidet:

- `stripeChargeFeesMinor`: Nicht-Application-Fee-Positionen der Charge-Buchung.
- `observedAccountLedgerNetMinor`: Summen tatsächlich gelesener eindeutiger
  Balance Transactions nach Kontokontext; Hauptbetrag und Transfers werden
  dadurch nicht doppelt als Gebühren gerechnet.
- `observedPlatformCostsMinor`: Der bislang belegte Kostenanteil der Plattform;
  bei Lücken noch kein vollständiger Gesamtwert.
- `platformBorneCostsMinor`: Vollständige, belegte Stripe-/Connect-Kosten dieses
  Audits auf der Plattform, andernfalls `null`.
- `platformLedgerObservedNetMinor`: Plattform-Nettoeffekt aus tatsächlich
  gelesenen Balance Transactions.
- `platformResidualMinor`: Vollständiger Saldo nach den zugeordneten Geldbewegungen
  und gegebenenfalls separat belegten Plattform-Verarbeitungskostenberichten;
  bei Lücken `null`.
- `platformFundingShortfallMinor`: `max(0, -platformResidualMinor)` aus einer
  vollständigen Kette, andernfalls `null`. Dies ist von den gesamten Stripe-
  Kosten und einer vertraglichen Serviceprovision zu unterscheiden.

Bei **Direct Charges mit application-collected processing fees** beweist eine
Nullgebühr in der Connected-Account-Buchung nicht, dass Gratilo keine Kosten hat.
Der Audit bleibt dafür unvollständig. Der bereits vorhandene interne,
geprüfte Plattform-Kostenbericht-Eingang
`StripeFeeAuditService.recordPlatformFeeEvidence(...)` reicht akzeptierte
Kostennachweise an neue Audit-Zeilen weiter. Er erzeugt keine historischen Audits
und ist kein öffentlicher Kosten-Schreibendpunkt. Quelle, Charge, Account und
Minor-Unit-Betrag werden festgehalten; derselbe Kostenstand wird nicht nochmals
addiert. Falls die Abgrenzung zu schon beobachteten Application-Fee-Buchungskosten
unklar ist, bleibt `PLATFORM_PROCESSING_COST_SCOPE_AMBIGUOUS` offen.

`COMPLETE` ist eine Aussage über die verifizierte Zahlungskette **zum Prüfzeitpunkt**,
keine Garantie, dass künftig keine periodischen Konto-/Connect-/Payout-Kosten
anfallen. Aggregierte Rechnungskosten werden ohne konkrete Zahlungszuordnung
nicht auf einzelne Zahlungen umgelegt. Ein Bank-Payout ist kein Transfer und
wird von dieser Funktion nicht ausgelöst.

## Geschützte Admin-API

Alle Endpunkte verlangen einen von der bestehenden JWT-Prüfung akzeptierten
Token mit Rolle **`ROLE_ADMIN`**. Rollen werden nicht aus einem freien Header
oder einer Query übernommen. Der Produktions-Sandbox-Zugangsfilter berücksichtigt
diese Admin-Endpunkte ausdrücklich; ein Admin muss nicht zusätzlich als Gast-
Testempfänger registriert sein. Es wird kein Admin-Benutzer automatisch angelegt.

`livemode` ist bei jeder Abfrage **verpflichtend**. Ergebnisse verschiedener Modi
werden weder automatisch zusammengeführt noch durch einen Default ausgewählt.
Alle Antworten enthalten `Cache-Control: no-store`. Die API liest nur gespeicherte
Audit-Daten und startet keine Stripe-Aktion oder Rekonstruktion.

```http
GET /payment-service/admin/stripe-payment-audits?reference=<interne-UUID-oder-pi_-ch_-tr_-ID>&livemode=false
GET /payment-service/admin/stripe-payment-audits/<interne-UUID>?livemode=false
GET /payment-service/admin/stripe-payment-audits/<interne-UUID>/events?livemode=false&after=0&limit=100
```

Die Suche unterstützt interne Payment-ID, PaymentIntent-ID, Charge-ID, Transfer-ID
und zusätzlich Session-ID. Sie liefert eine Liste getrennter Zahlungen mit ihren
Kontokontexten. Ein historischer/nicht aufgenommener Zahlungsbezug liefert keine
Treffer; ein direkter Lookup liefert HTTP 404. Es entsteht dabei kein Audit.
Anonyme Aufrufe sind HTTP 401, normale Benutzer HTTP 403, fehlender/ungültiger
Modus oder ungültige Suchreferenzen HTTP 400.

Ereignisse sind nach aufsteigender ID paginiert. Die Antwort enthält `events`,
`hasMore` und `nextAfter`; für Folgeseiten wird `after=nextAfter` gesetzt.
`limit` liegt zwischen 1 und 500. Die aktuelle finanzielle Zahlungskette umfasst
alle gespeicherten aktuellen Transfers, keine auf zwei Mitglieder gekürzte Auswahl.

### Beispielantwort

Gekürztes **synthetisches Fixture-Beispiel**, keine ausgeführte Zahlung und keine
historischen Stripe-IDs. Hier sind alle Beziehungen und Kosten vollständig;
die Balance-Buchungen können trotzdem noch `pending` sein.

```json
{
  "paymentId": "00000000-0000-4000-8000-000000000123",
  "paymentType": "POOL",
  "livemode": false,
  "platformAccountId": "acct_platform",
  "chargeAccountId": null,
  "checkoutSessionId": "cs_test_example",
  "paymentIntentId": "pi_example",
  "chargeId": "ch_example",
  "balanceTransactionId": "txn_charge",
  "status": "COMPLETE",
  "lastError": null,
  "revision": 3,
  "enrolledAt": "2026-10-29T10:00:00Z",
  "checkedAt": "2026-10-29T10:01:00Z",
  "missingData": [],
  "financialData": {
    "paymentCreatedAtUtc": "2026-10-29T10:00:05Z",
    "expectedGrossMinor": 500,
    "recipientTargetMinor": 430,
    "paymentBalance": {
      "id": "txn_charge",
      "account": "acct_platform",
      "source": "ch_example",
      "amount": 500,
      "fee": 40,
      "net": 460,
      "currency": "eur",
      "type": "charge",
      "reporting_category": "charge",
      "status": "pending",
      "fee_details": [
        {
          "amount": 40,
          "currency": "eur",
          "type": "stripe_fee",
          "application": null,
          "description": "Stripe processing fees"
        }
      ]
    },
    "transfers": [
      {
        "id": "tr_example_one",
        "source_transaction": "ch_example",
        "destination": "acct_recipient_one",
        "amount": 215,
        "currency": "eur",
        "amount_reversed": 0,
        "balance_transaction": "txn_transfer_one",
        "destination_payment": "py_example_one",
        "recipient_balance_transaction": "txn_recipient_one",
        "recipientBalance": {
          "id": "txn_recipient_one",
          "source": "py_example_one",
          "account": "acct_recipient_one",
          "amount": 215,
          "fee": 0,
          "net": 215,
          "currency": "eur",
          "status": "pending",
          "fee_details": []
        },
        "reversals": []
      },
      {
        "id": "tr_example_two",
        "source_transaction": "ch_example",
        "destination": "acct_recipient_two",
        "amount": 215,
        "currency": "eur",
        "amount_reversed": 0,
        "balance_transaction": "txn_transfer_two",
        "destination_payment": "py_example_two",
        "recipient_balance_transaction": "txn_recipient_two",
        "reversals": []
      }
    ],
    "refunds": [],
    "disputes": [],
    "stripeChargeFeesMinor": 40,
    "platformBorneCostsMinor": 40,
    "platformLedgerObservedNetMinor": 30,
    "platformResidualMinor": 30,
    "platformFundingShortfallMinor": 0,
    "completeness": "COMPLETE",
    "missingData": []
  }
}
```

Die ungekürzte Antwort enthält außerdem die verifizierten Plattform-/Empfänger-
Balance-Objekte je Transfer, die Charge-Versuche, die Gebührenpositionsliste sowie
gegebenenfalls Refund-/Reversal-/Dispute- und Application-Fee-Daten. Ereignisse
werden über den separaten paginierten Endpunkt gelesen.

## Test-/Live-Trennung und Datenschutz

Das bestehende Gratilo-Zahlungsmodul bleibt **test-only**. Die neue Funktion
schaltet keine Livezahlungen frei. Der Audit speichert den Modus ausdrücklich;
seine Tabellen und API können Modi unterscheiden. Der aktuelle Stripe-Lesetransport
akzeptiert wie der bestehende Zahlungsprozess ausschließlich Testschlüssel und
verarbeitet nur zu diesem Modus passende Audit-Zeilen. Ein nicht passender Modus,
ein anderes eingefrorenes Plattformkonto oder eine fremde Source-Beziehung wird
nicht als Zahlungsevidenz übernommen.

Es werden keine Stripe-Schlüssel, Client-Secrets, PANs, CVCs, Karten-/Billing-
Details, Kundenkontakte oder freie Zahlungs-/Dispute-Metadaten gespeichert.
Gebührenbeschreibungen werden begrenzt und von erkennbaren Secret-, E-Mail- und
langen Kartennummernmustern bereinigt. Fehlerdaten enthalten feste Codes, keine
rohen Provider-Exceptions oder API-Antworten.

## Testergebnisse und Reproduktion

Die vollständige Payment-Service-Suite wurde mit **283 Tests, 0 Fehlern und
0 übersprungenen Tests** ausgeführt. Es wurden Mocks/Fixtures und eine isolierte
lokale PostgreSQL-15-Datenbank verwendet. In dieser Umsetzung wurden keine echten
Stripe-Anfragen für Zahlungen und keine neuen Sandbox-Zahlungen ausgeführt.

Abgedeckt sind unter anderem:

- Neue Einzelzahlung einschließlich Direct-Charge-Kontokontext, Application Fee
  und geprüfter Plattform-Kostenevidenz; unbekannte Kosten bleiben `null`.
- Neue Poolzahlungen mit mehreren Empfängern und 125 Transfers über zwei Seiten.
- PI-/Charge-/Balance-/Transfer-/Account-Zuordnung und Ablehnung fremder Quellen,
  falscher Modi, falscher Beträge oder unvollständiger Gebührenarithmetik.
- Gebührenpositionen, tatsächliche Nullgebühren, Plattformkosten und Zuschuss,
  offene Verteilungen und nicht lesbare Empfängerseiten.
- Verzögerte Balance-Daten, Wiederholungen ohne doppelte Snapshots oder Gebühren,
  parallele Reconciliation und parallele doppelte Webhooks.
- Signaturprüfung vor Audit-Erfassung, Duplikate, falscher Account/Modus und
  keine Aufnahme historischer Zahlungen.
- Refunds, fehlgeschlagene Refunds mit Wiederherstellung der Mittel,
  Transfer-Reversals und verknüpfte Empfänger-Rückbuchungen.
- Dispute-Buchungen mit weiteren belegten Gebühren ohne persönliche Evidence-Daten.
- Admin-HTTP-401/403, Rolle `ROLE_ADMIN`, Modusfilter, verpflichtender Modus,
  Transfer-Suche, Ereignisseiten und Cache-Schutz.
- Reale Liquibase-Aktualisierung einer vorhandenen isolierten Datenbank:
  eine vorher angelegte Zahlungszeile bleibt über `row_to_json` unverändert;
  alle vier neuen Audit-Tabellen sind nach dem Upgrade leer.
- PostgreSQL-FKs/Mode-Trennung, idempotente Inserts/Upserts, tatsächliche
  parallele Row-Locks sowie Ablehnung von Ereignis-UPDATE, DELETE und TRUNCATE.
- Die bestehenden Checkout-, Settlement-, Preis-, Status-, Refund- und
  Benachrichtigungstests bleiben erfolgreich. Zusätzliche Checkout-Assertions
  prüfen die tatsächlichen Neuanlage-Hooks und ausbleibende Aufnahme beim Resume.

Standardlauf mit Mocks:

```bash
./gradlew :backend:payment-service:test --console=plain
```

Der PostgreSQL-Vertragstest ist ohne die ausdrücklich benannte lokale Test-URL
übersprungen. Für den oben genannten vollständigen Lauf wurde er explizit aktiviert:

```bash
docker run --rm -d --name gratilo-audit-test-db \
  -e POSTGRES_PASSWORD=audit-test-only -e POSTGRES_DB=audit_test \
  -p 127.0.0.1:55441:5432 postgres:15

AUDIT_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:55441/audit_test \
  ./gradlew :backend:payment-service:test --console=plain

docker stop gratilo-audit-test-db
```

JDK 21 ist erforderlich. Die Testklasse akzeptiert ausschließlich genau diese
lokale Datenbank-URL und verwendet eine nur für diese Wegwerfdatenbank gedachte
Fixture-Zugangsdatenkombination. Sie baut ihr Schema neu auf; niemals auf eine
Entwicklungs-, Deployment- oder Produktionsdatenbank umleiten. Es wurde keine
Migration an der laufenden Gratilo-Datenbank und kein Deployment ausgeführt.
