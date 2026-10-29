# Stripe-Sandbox: protokollierte Einzel- und Poolzahlung

## Ergebnis und Testumfang

**Beide Tests erfolgreich ausgeführt und über die Stripe-API zurückgelesen.**
Testlauf `gratilo-sct-compare-20261029T084123Z`, Plattform `acct_1UQNErJnhDgmiMAM`, `livemode=false`.
Abschluss der Finanztests: 2026-10-29T08:48:30.902295+00:00. Abschluss der zusätzlichen
lesenden Bestandsprüfung: 2026-10-29T08:49:13.609375+00:00.

| Ergebnis | Einzeltest | Pooltest |
|---|---:|---:|
| Tatsächlich belastet | **25,63 EUR** | **25,63 EUR** |
| Tatsächliche Stripe-Gebühr | **1,06 EUR** | **1,06 EUR** |
| Zahlungsnetto auf der Plattform | **24,57 EUR** | **24,57 EUR** |
| Tatsächliche Empfänger-Netto-Gutschriften laut Balance Transactions | **25,00 EUR an ein Konto** | **12,50 EUR + 12,50 EUR an zwei Konten** |
| Zusätzliche Gebühr auf Empfängerbuchungen | **0,00 EUR** | **0,00 EUR + 0,00 EUR** |
| Zugeordnete Transfers | **1** | **2** |
| Saldo der Plattform-Zahlungs-/Transferbuchungen | **−0,43 EUR** | **−0,43 EUR** |
| Empfänger-Balance-Status | `pending` | beide `pending` |

Gesamt: **51,26 EUR** Testbelastung, **2,12 EUR** Stripe-Gebühren,
**50,00 EUR** Empfängernetto, **0,86 EUR** von der Plattform getragene Differenz.
Alle Beträge sind Sandbox-Buchungen, keine Livezahlungen oder echten Banküberweisungen.

Diese Tests wurden nach dem ausdrücklichen Folgeauftrag des Nutzers neu angelegt.
Für die offene Betragspräferenz wurde nach Vorankündigung der historische
Vergleichswert von **jeweils 25,63 EUR Gesamtzahlung** gewählt. Es sind
**isolierte SCT-API-Vergleichstests**: ein Empfänger beziehungsweise zwei
Empfänger mit 50/50-Verteilung und einem festen Test-Empfängerbudget von je
25,00 EUR. Es wurde keine Gratilo-Serviceprovision erhoben.

**Abnahmegrenze:** Die Tests bestätigen die tatsächliche Stripe-Zahlungs- und
SCT-Transferkette einschließlich der Empfängerbuchungen. Sie sind keine
Abnahme der Gratilo-Benutzeroberfläche, des gehosteten Checkout-Ablaufs,
der internen Pool-/Benutzerzuordnung oder der aktuellen Direct-Charge-
Implementierung für Einzelzahlungen. Es wurde kein bestehender Gratilo-Pool
umkonfiguriert und kein Gratilo-Zahlungsdatensatz simuliert. Die offene
Produktentscheidung zum Gebührenmodell wird hierdurch nicht freigegeben.

## Zugang, Empfänger und Schutzmaßnahmen

Der vorhandene Testschlüssel wurde ausschließlich im Prozessspeicher aus dem
benannten AWS-Secrets-Manager-Eintrag `gratilo/prd/stripe-test` gelesen.
`GET /v1/account` bestätigte `acct_1UQNErJnhDgmiMAM` und die Gratilo-Bezeichnung;
`GET /v1/balance` bestätigte `livemode=false`. Live-Schlüssel wurden durch den
Transport abgewiesen. Die separate lokale Sandbox wurde nicht verwendet.

Vor jedem Ausführungslauf wurden die bestehenden Empfängerkonten lesend geprüft:

| Empfänger | Testmodus | Geschlossen | Transfer-Capability | Gebührenverantwortung | Bank-Payout-Plan |
|---|---|---|---|---|---|
| `acct_1UTKTHJnhD0xjF0b` | `livemode=false` | `false` | `active` | `application` | `manual` |
| `acct_1UTK7rJnhDvqBsAp` | `livemode=false` | `false` | `active` | `application` | `manual` |

Die Konten waren bereits offen, transferbereit und auf manuelle Bankauszahlungen
konfiguriert. Keine dieser Einstellungen wurde geändert. Der ausgewiesene
Empfänger-Nettozufluss ist eine **Stripe-Balance-Gutschrift**, keine bereits
verfügbare oder auf ein Bankkonto ausgezahlte Zahlung; die Buchungen sind derzeit
`pending`. Es wurden keine Payouts ausgelöst.

Verwendete öffentliche Stripe-Testfixture: `pm_card_visa`; keine echten Karten,
Kundenkontakte, Bankdaten oder personenbezogenen Zahlungseingaben. Die Stripe-
Request-IDs und die ausgewählten Finanzfelder wurden protokolliert; Schlüssel,
PaymentIntent-Client-Secrets und vollständige ungeschützte API-Dumps wurden nicht gespeichert.

Die Testmetadaten `verification_run_id`, `verification_case` und
`verification_only=true` markieren die neuen Vorgänge eindeutig. Es wurde keine
historische `transaction_id` oder `pool_id` als Metadatum wiederverwendet.
Die beiden Transfergruppen sind getrennt. Die Geldbewegungen wurden nach jeder
Erstellung über unabhängige GET-Abrufe verifiziert. Idempotenzkennungen und
Fehler sind im vollständigen Aufrufprotokoll erfasst.

## Einzeltest: ein SCT-Empfänger

| Feld | Tatsächlicher Stripe-Wert |
|---|---|
| PaymentIntent | `pi_3UVp0JJnhDgmiMAM1jqIMm5A` |
| Charge | `ch_3UVp0JJnhDgmiMAM1IDZUMUL` |
| Zahlungs-Balance-Transaction | `txn_3UVp0JJnhDgmiMAM1kNioTjT` |
| Plattform / Kontokontext | `acct_1UQNErJnhDgmiMAM` |
| Erstellung UTC | 2026-10-29 08:48:12 UTC |
| Status | PI `succeeded`; Charge `succeeded`, `paid=true`, `captured=true`; `livemode=false` |
| Betrag | PI `amount=2563`, `amount_received=2563`; Charge `amount=2563`, `amount_captured=2563`; Währung EUR |
| Erstattungsstand | Charge `amount_refunded=0`, `refunded=false` |
| Transfergruppe | `gratilo-sct-compare-20261029T084123Z-single` |
| Checkout-Session / Gratilo-Transaktions-ID | Nicht angelegt; direkter PaymentIntent-API-Test |
| `transfer_data` / Application-Fee-Referenz | `null` / `null` |
| Gruppensuche / Charge-Liste | vollständig; exakt 1 zugehörige Transfers und eine Charge |

### Zahlungsgebühr und Nettobuchung

| Feld | API-Wert |
|---|---|
| `source` | `ch_3UVp0JJnhDgmiMAM1IDZUMUL` |
| `amount` | `2563` Cent = 25,63 EUR |
| `fee` | `106` Cent = 1,06 EUR |
| `net` | `2457` Cent = 24,57 EUR |
| `currency` | `eur` |
| `type` / `reporting_category` | `charge` / `charge` |
| `status` | `pending` |
| `created` | 2026-10-29 08:48:12 UTC |
| `available_on` | 2026-11-05 00:00:00 UTC |
| `exchange_rate` | `null` |

Vollständige `fee_details` dieser Zahlungsbuchung:

```json
[
  {
    "amount": 106,
    "application": null,
    "currency": "eur",
    "description": "Stripe processing fees",
    "type": "stripe_fee"
  }
]
```

Centprüfung: **`2563 − 106 = 2457`**, erfüllt.
Der Source-gefilterte Balance-Abruf lieferte vollständig genau diese
Zahlungs-Balance-Transaction. Es wurde keine Gebührenposition aus der anderen
Testzahlung zugerechnet.

### Transfers und Empfängergutschriften

Transfer 1:

| Feld | API-Wert |
|---|---|
| Transfer-ID | `tr_3UVp0JJnhDgmiMAM1KFb8sQO` |
| Betrag / Währung | `2500` Cent / `eur` = 25,00 EUR |
| Erstellt UTC | 2026-10-29 08:48:14 UTC |
| Testmodus / Reversals | `livemode=false`, `amount_reversed=0`, `reversed=false`; vollständige Reversal-Liste leer |
| `source_transaction` | `ch_3UVp0JJnhDgmiMAM1IDZUMUL` |
| `transfer_group` | `gratilo-sct-compare-20261029T084123Z-single` |
| `destination` | `acct_1UTKTHJnhD0xjF0b` |
| Plattform-Transfer-Balance-Transaction | `txn_3UVp0JJnhDgmiMAM1W8oLUIF` |
| Plattformbuchung | `source=tr_3UVp0JJnhDgmiMAM1KFb8sQO`, `amount=-2500`, `fee=0`, `net=-2500` Cent; `eur`, `type=transfer`, `reporting_category=transfer`, `status=pending` |
| Empfängerzahlung (`destination_payment`) | `py_1UVp0MJnhD0xjF0b85viWYIp` |
| Empfängerzahlungsstatus | `paid=true`, `status=succeeded`, `amount=2500`, `currency=eur`, `livemode=false` |
| Empfänger-Balance-Transaction | `txn_1UVp0MJnhD0xjF0buWrBb5S7` |
| Empfängerbuchung | `source=py_1UVp0MJnhD0xjF0b85viWYIp`, `amount=2500`, `fee=0`, `net=2500` Cent; `eur`, `type=payment`, `reporting_category=charge`, `status=pending` |
| Verfügbar laut Empfängerbuchung | 2026-11-05 00:00:00 UTC |

`fee_details` der Plattform-Transferbuchung: `[]`.
`fee_details` der Empfängerbuchung: `[]`.

Centprüfungen: `-2500 − 0 = -2500` auf der Plattform
und `2500 − 0 = 2500` beim Empfänger, jeweils erfüllt.
Die Empfängerbuchung wurde mit `Stripe-Account: acct_1UTKTHJnhD0xjF0b` gelesen;
`source` entspricht exakt der am Transfer referenzierten Empfängerzahlung.
Es wurde kein zusätzlicher Empfänger-Gebührenabzug vorgenommen oder geschätzt.

Tatsächlich gebuchte Empfängernettosumme: **25,00 EUR**.
Plattform-Saldo der zugeordneten Buchungen:
`2457  − 2500 = -43` Cent.
Damit trägt Gratilo in diesem Test **0,43 EUR**
Differenz; die festgelegte Empfänger-Nettosumme bleibt unverändert.

[PaymentIntent im Test-Dashboard](https://dashboard.stripe.com/test/payments/pi_3UVp0JJnhDgmiMAM1jqIMm5A);
vorher das Plattformkonto `acct_1UQNErJnhDgmiMAM` auswählen.

## Pooltest: zwei SCT-Empfänger

| Feld | Tatsächlicher Stripe-Wert |
|---|---|
| PaymentIntent | `pi_3UVp0OJnhDgmiMAM1HD1rpdp` |
| Charge | `ch_3UVp0OJnhDgmiMAM1zFeNZFK` |
| Zahlungs-Balance-Transaction | `txn_3UVp0OJnhDgmiMAM1zMKF70t` |
| Plattform / Kontokontext | `acct_1UQNErJnhDgmiMAM` |
| Erstellung UTC | 2026-10-29 08:48:17 UTC |
| Status | PI `succeeded`; Charge `succeeded`, `paid=true`, `captured=true`; `livemode=false` |
| Betrag | PI `amount=2563`, `amount_received=2563`; Charge `amount=2563`, `amount_captured=2563`; Währung EUR |
| Erstattungsstand | Charge `amount_refunded=0`, `refunded=false` |
| Transfergruppe | `gratilo-sct-compare-20261029T084123Z-pool` |
| Checkout-Session / Gratilo-Transaktions-ID | Nicht angelegt; direkter PaymentIntent-API-Test |
| `transfer_data` / Application-Fee-Referenz | `null` / `null` |
| Gruppensuche / Charge-Liste | vollständig; exakt 2 zugehörige Transfers und eine Charge |

### Zahlungsgebühr und Nettobuchung

| Feld | API-Wert |
|---|---|
| `source` | `ch_3UVp0OJnhDgmiMAM1zFeNZFK` |
| `amount` | `2563` Cent = 25,63 EUR |
| `fee` | `106` Cent = 1,06 EUR |
| `net` | `2457` Cent = 24,57 EUR |
| `currency` | `eur` |
| `type` / `reporting_category` | `charge` / `charge` |
| `status` | `pending` |
| `created` | 2026-10-29 08:48:17 UTC |
| `available_on` | 2026-11-05 00:00:00 UTC |
| `exchange_rate` | `null` |

Vollständige `fee_details` dieser Zahlungsbuchung:

```json
[
  {
    "amount": 106,
    "application": null,
    "currency": "eur",
    "description": "Stripe processing fees",
    "type": "stripe_fee"
  }
]
```

Centprüfung: **`2563 − 106 = 2457`**, erfüllt.
Der Source-gefilterte Balance-Abruf lieferte vollständig genau diese
Zahlungs-Balance-Transaction. Es wurde keine Gebührenposition aus der anderen
Testzahlung zugerechnet.

### Transfers und Empfängergutschriften

Transfer 1:

| Feld | API-Wert |
|---|---|
| Transfer-ID | `tr_3UVp0OJnhDgmiMAM1PPJI308` |
| Betrag / Währung | `1250` Cent / `eur` = 12,50 EUR |
| Erstellt UTC | 2026-10-29 08:48:19 UTC |
| Testmodus / Reversals | `livemode=false`, `amount_reversed=0`, `reversed=false`; vollständige Reversal-Liste leer |
| `source_transaction` | `ch_3UVp0OJnhDgmiMAM1zFeNZFK` |
| `transfer_group` | `gratilo-sct-compare-20261029T084123Z-pool` |
| `destination` | `acct_1UTKTHJnhD0xjF0b` |
| Plattform-Transfer-Balance-Transaction | `txn_3UVp0OJnhDgmiMAM1SqlQ7im` |
| Plattformbuchung | `source=tr_3UVp0OJnhDgmiMAM1PPJI308`, `amount=-1250`, `fee=0`, `net=-1250` Cent; `eur`, `type=transfer`, `reporting_category=transfer`, `status=pending` |
| Empfängerzahlung (`destination_payment`) | `py_1UVp0RJnhD0xjF0bqzE5TT73` |
| Empfängerzahlungsstatus | `paid=true`, `status=succeeded`, `amount=1250`, `currency=eur`, `livemode=false` |
| Empfänger-Balance-Transaction | `txn_1UVp0RJnhD0xjF0bmidV0bX3` |
| Empfängerbuchung | `source=py_1UVp0RJnhD0xjF0bqzE5TT73`, `amount=1250`, `fee=0`, `net=1250` Cent; `eur`, `type=payment`, `reporting_category=charge`, `status=pending` |
| Verfügbar laut Empfängerbuchung | 2026-11-05 00:00:00 UTC |

`fee_details` der Plattform-Transferbuchung: `[]`.
`fee_details` der Empfängerbuchung: `[]`.

Centprüfungen: `-1250 − 0 = -1250` auf der Plattform
und `1250 − 0 = 1250` beim Empfänger, jeweils erfüllt.
Die Empfängerbuchung wurde mit `Stripe-Account: acct_1UTKTHJnhD0xjF0b` gelesen;
`source` entspricht exakt der am Transfer referenzierten Empfängerzahlung.
Es wurde kein zusätzlicher Empfänger-Gebührenabzug vorgenommen oder geschätzt.

Transfer 2:

| Feld | API-Wert |
|---|---|
| Transfer-ID | `tr_3UVp0OJnhDgmiMAM1e9upw7h` |
| Betrag / Währung | `1250` Cent / `eur` = 12,50 EUR |
| Erstellt UTC | 2026-10-29 08:48:26 UTC |
| Testmodus / Reversals | `livemode=false`, `amount_reversed=0`, `reversed=false`; vollständige Reversal-Liste leer |
| `source_transaction` | `ch_3UVp0OJnhDgmiMAM1zFeNZFK` |
| `transfer_group` | `gratilo-sct-compare-20261029T084123Z-pool` |
| `destination` | `acct_1UTK7rJnhDvqBsAp` |
| Plattform-Transfer-Balance-Transaction | `txn_3UVp0OJnhDgmiMAM1jfmZKJb` |
| Plattformbuchung | `source=tr_3UVp0OJnhDgmiMAM1e9upw7h`, `amount=-1250`, `fee=0`, `net=-1250` Cent; `eur`, `type=transfer`, `reporting_category=transfer`, `status=pending` |
| Empfängerzahlung (`destination_payment`) | `py_1UVp0YJnhDvqBsApO2IqEPKM` |
| Empfängerzahlungsstatus | `paid=true`, `status=succeeded`, `amount=1250`, `currency=eur`, `livemode=false` |
| Empfänger-Balance-Transaction | `txn_1UVp0ZJnhDvqBsApXjdOtPKx` |
| Empfängerbuchung | `source=py_1UVp0YJnhDvqBsApO2IqEPKM`, `amount=1250`, `fee=0`, `net=1250` Cent; `eur`, `type=payment`, `reporting_category=charge`, `status=pending` |
| Verfügbar laut Empfängerbuchung | 2026-11-05 00:00:00 UTC |

`fee_details` der Plattform-Transferbuchung: `[]`.
`fee_details` der Empfängerbuchung: `[]`.

Centprüfungen: `-1250 − 0 = -1250` auf der Plattform
und `1250 − 0 = 1250` beim Empfänger, jeweils erfüllt.
Die Empfängerbuchung wurde mit `Stripe-Account: acct_1UTK7rJnhDvqBsAp` gelesen;
`source` entspricht exakt der am Transfer referenzierten Empfängerzahlung.
Es wurde kein zusätzlicher Empfänger-Gebührenabzug vorgenommen oder geschätzt.

Tatsächlich gebuchte Empfängernettosumme: **25,00 EUR**.
Plattform-Saldo der zugeordneten Buchungen:
`2457  − 1250 − 1250 = -43` Cent.
Damit trägt Gratilo in diesem Test **0,43 EUR**
Differenz; die festgelegte Empfänger-Nettosumme bleibt unverändert.

[PaymentIntent im Test-Dashboard](https://dashboard.stripe.com/test/payments/pi_3UVp0OJnhDgmiMAM1HD1rpdp);
vorher das Plattformkonto `acct_1UQNErJnhDgmiMAM` auswählen.

## Verknüpfungsnachweise und Kontrollsummen

| Beziehung | Geprüftes Stripe-Feld |
|---|---|
| PaymentIntent → Charge | `latest_charge`; Gegenprüfung `charge.payment_intent`; vollständige Charge-Liste je PI enthält genau ein Objekt |
| Charge → Zahlungsbuchung | `charge.balance_transaction`; Gegenprüfung `balance_transaction.source=charge.id` |
| Charge → Transfer | `transfer.source_transaction=charge.id` und exakte Fall-Transfergruppe |
| Transfer → Plattformbuchung | `transfer.balance_transaction`; Gegenprüfung `balance_transaction.source=transfer.id` |
| Transfer → Empfängerkonto | `transfer.destination` |
| Transfer → Empfängerzahlung | `transfer.destination_payment`; Abruf im genau zugehörigen `Stripe-Account`-Kontext |
| Empfängerzahlung → Empfängerbuchung | `destination_payment.balance_transaction`; Gegenprüfung `balance_transaction.source=destination_payment.id` |
| Testfall → Bestandsliste | `metadata.verification_run_id`; vollständige PI-Liste enthält genau zwei Objekte dieses Testlaufs |

Nach dem Lauf wurde die Plattform-PaymentIntent-Liste vollständig erneut gelesen:
34 Objekte insgesamt,
**exakt zwei PaymentIntents dieses Testlaufs**, beide `succeeded` und im Testmodus.
Es wurden exakt zwei erfolgreiche PaymentIntent-Erstellungen und drei erfolgreiche
Transfer-Erstellungen protokolliert. Die nach Transfergruppe gelesenen Listen
entsprechen genau den erstellten Transfer-ID-Mengen; es gibt keinen zusätzlichen
Transfer dieses Testlaufs. Pro PI wurde exakt eine zugehörige Charge verifiziert.

Die vollständige Plattform-Application-Fee-Liste enthielt
13 Objekte;
**keine** ist den beiden neuen Charges zugeordnet. Auch die Charge-/PI-Felder
enthalten keine Application Fee. Test-Serviceprovision: 0 Cent. Dies ist eine
Eigenschaft dieser Vergleichsfälle und keine neue Gratilo-Preisentscheidung.

Alle folgenden unabhängigen Prüfbedingungen wurden erfolgreich ausgewertet:

```json
{
  "two_successful_payment_intents": true,
  "three_transfers_exactly": true,
  "total_charged_5126": true,
  "total_fees_212": true,
  "total_recipient_net_5000": true,
  "platform_cost_86": true,
  "all_source_links_exact": true,
  "no_mutation_of_other_object_types": true,
  "exact_successful_write_budget": true
}
```

Der Satz „Plattformkosten trägt Gratilo“ wird hier durch den negativen
Zahlungs-/Transfersaldo bei gleichzeitig vollständigen Empfängergutschriften
belegt. Er garantiert weder einen zukünftigen Tarif noch die Tragung sämtlicher
separat berechneter Connect-/Konto-/Payout-Kosten. Periodische/aggregierte Kosten
benötigen eigene Rechnungsnachweise. Es wurde kein Bank-Payout getestet.

## Fehler und Wiederholungen vor den erfolgreichen Zahlungen

Der erste PaymentIntent-Aufruf enthielt den inzwischen abgelösten Parameter
`payment_method_types[]` und wurde mit HTTP 400 abgewiesen. Eine identische
Wiederholung mit derselben Idempotenzkennung wurde zur gefilterten
Fehlerdiagnose verwendet und ebenfalls abgewiesen. Stripe nannte den nicht
mehr unterstützten Parameter; Kontoeinstellungen wurden nicht verändert.

Anschließend wurde der dokumentierte Parameter `allowed_payment_method_types[]`
verwendet. Stripe lehnte zunächst die Wiederverwendung der alten
Idempotenzkennung mit geänderten Parametern als `idempotency_error` ab.
**Vor einer korrigierten Erstellung wurde vollständig lesend abgeglichen:**
Die Plattformliste enthielt 32 PaymentIntents, aber keinen mit der Testlauf-ID
oder einer der beiden Test-Transfergruppen. Kein vorheriger fehlgeschlagener
Aufruf hatte einen PaymentIntent dieses Testlaufs erzeugt.

Für den korrigierten Parametersatz wurden innerhalb desselben Testlaufs die
Payment-Idempotenzkennungen mit dem eindeutigen Suffix `:params-v2` verwendet.
Erst diese Aufrufe erzeugten die oben dokumentierten zwei PaymentIntents.
Die drei Fehlerantworten und alle Idempotenzkennungen bleiben im Nachweis erhalten.
Es wurden keine zusätzlichen Zahlungen zur Fehlerbehebung und keine Refunds
oder Rückübertragungen erzeugt.

Offizielle Grundlagen: [Stripe-Testfixtures](https://docs.stripe.com/testing),
[PaymentIntent-Erstellung](https://docs.stripe.com/api/payment_intents/create),
[Transfer-Erstellung und Source-Verknüpfung](https://docs.stripe.com/api/transfers/create).
Die konkreten Beträge und Objekt-IDs in diesem Protokoll stammen aus den
API-Antworten, nicht aus Dokumentationsbeispielen oder einer Tarifformel.

## Vollständiges gefiltertes API-Protokoll

[Maschinenlesbarer Nachweis](stripe-sandbox-single-pool-test-evidence.json).
Das JSON enthält die ausgewählten Objektfelder, vollständige `fee_details`,
Preflight-Befunde, vor/nach gelesene Test-Balance-Snapshots, Idempotenzkennungen,
Parameter und sämtliche HTTP-/Request-ID-Nachweise. Es ist kein vollständiger
API-Dump und enthält keine Authentifizierungs- oder Client-Secrets.

SHA256 des JSON-Nachweises: `8c544b823912cf02a27cea2bb05fd08313ad47b0ef9f0f008fded0a5ba459c51`.

| Start UTC | Methode | Kontokontext | Endpunkt | HTTP | Stripe Request-ID |
|---|---|---|---|---:|---|
| 2026-10-29T08:41:24.535247+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/balance` | 200 | `req_gPok5NopzwbOFF` |
| 2026-10-29T08:41:24.856719+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/account` | 200 | `req_r4tgKsw37T49Qk` |
| 2026-10-29T08:41:25.255814+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v2/core/accounts/acct_1UTKTHJnhD0xjF0b` | 200 | `req_v2aOcWq4wTqsUF9HZ` |
| 2026-10-29T08:41:25.831547+00:00 | GET | `acct_1UTKTHJnhD0xjF0b` | `/v1/balance` | 200 | `req_5agWaqyf10dvqr` |
| 2026-10-29T08:41:26.314219+00:00 | GET | `acct_1UTKTHJnhD0xjF0b` | `/v1/balance_settings` | 200 | `req_nXFQ1pSZAn9jIv` |
| 2026-10-29T08:41:26.649502+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v2/core/accounts/acct_1UTK7rJnhDvqBsAp` | 200 | `req_v2AAhF9fwTojxYhtt` |
| 2026-10-29T08:41:27.046212+00:00 | GET | `acct_1UTK7rJnhDvqBsAp` | `/v1/balance` | 200 | `req_zIhIZVy23PPITL` |
| 2026-10-29T08:41:27.413639+00:00 | GET | `acct_1UTK7rJnhDvqBsAp` | `/v1/balance_settings` | 200 | `req_ewOM2siMrw1jgv` |
| 2026-10-29T08:44:11.280527+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/balance` | 200 | `req_DLCCYyOPWl73tg` |
| 2026-10-29T08:44:11.595291+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/account` | 200 | `req_1TWwktsVDuwE7W` |
| 2026-10-29T08:44:11.984555+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v2/core/accounts/acct_1UTKTHJnhD0xjF0b` | 200 | `req_v2rxtzx6KYv0yIp6f` |
| 2026-10-29T08:44:12.376133+00:00 | GET | `acct_1UTKTHJnhD0xjF0b` | `/v1/balance` | 200 | `req_2TY2i8yRKP6iTi` |
| 2026-10-29T08:44:12.760788+00:00 | GET | `acct_1UTKTHJnhD0xjF0b` | `/v1/balance_settings` | 200 | `req_pi7UgLpG2uQKEI` |
| 2026-10-29T08:44:13.123992+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v2/core/accounts/acct_1UTK7rJnhDvqBsAp` | 200 | `req_v2Br4skXcPBp0GOfl` |
| 2026-10-29T08:44:13.601747+00:00 | GET | `acct_1UTK7rJnhDvqBsAp` | `/v1/balance` | 200 | `req_L4NJCCpd9pmXWl` |
| 2026-10-29T08:44:14.038028+00:00 | GET | `acct_1UTK7rJnhDvqBsAp` | `/v1/balance_settings` | 200 | `req_xolqHX0lIjcAQR` |
| 2026-10-29T08:44:14.365141+00:00 | POST | `acct_1UQNErJnhDgmiMAM` | `/v1/payment_intents` | 400 | `req_iJzWT6QMlHmzNQ` |
| 2026-10-29T08:45:23.627749+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/balance` | 200 | `req_AmEbswzd21M68R` |
| 2026-10-29T08:45:23.969939+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/account` | 200 | `req_GDawDfTlz4dGJQ` |
| 2026-10-29T08:45:24.368967+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v2/core/accounts/acct_1UTKTHJnhD0xjF0b` | 200 | `req_v2NJY2vJgQNXB26YP` |
| 2026-10-29T08:45:24.769332+00:00 | GET | `acct_1UTKTHJnhD0xjF0b` | `/v1/balance` | 200 | `req_RcpEV8cuwhZRtq` |
| 2026-10-29T08:45:25.251662+00:00 | GET | `acct_1UTKTHJnhD0xjF0b` | `/v1/balance_settings` | 200 | `req_GngrwnUJpqTpEt` |
| 2026-10-29T08:45:25.638176+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v2/core/accounts/acct_1UTK7rJnhDvqBsAp` | 200 | `req_v2zd4OUyTaseeqz3X` |
| 2026-10-29T08:45:26.042000+00:00 | GET | `acct_1UTK7rJnhDvqBsAp` | `/v1/balance` | 200 | `req_CWBQwsWbACNq1z` |
| 2026-10-29T08:45:26.445451+00:00 | GET | `acct_1UTK7rJnhDvqBsAp` | `/v1/balance_settings` | 200 | `req_LyboYaV4HY0wfk` |
| 2026-10-29T08:45:26.770361+00:00 | POST | `acct_1UQNErJnhDgmiMAM` | `/v1/payment_intents` | 400 | `req_EbRgNZ3w631tje` |
| 2026-10-29T08:46:15.720692+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/payment_intents` | 200 | `req_uwS0I5M7VDSSpO` |
| 2026-10-29T08:46:45.439263+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/balance` | 200 | `req_Nf49K5ctPUqZob` |
| 2026-10-29T08:46:45.777386+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/account` | 200 | `req_EMtllAMFX5P5PJ` |
| 2026-10-29T08:46:46.139684+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v2/core/accounts/acct_1UTKTHJnhD0xjF0b` | 200 | `req_v2hDqYvQqBgSoE7eb` |
| 2026-10-29T08:46:46.518380+00:00 | GET | `acct_1UTKTHJnhD0xjF0b` | `/v1/balance` | 200 | `req_uKqNgdwVOPD6mQ` |
| 2026-10-29T08:46:46.934060+00:00 | GET | `acct_1UTKTHJnhD0xjF0b` | `/v1/balance_settings` | 200 | `req_m6Wcjnha99ArTe` |
| 2026-10-29T08:46:47.240419+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v2/core/accounts/acct_1UTK7rJnhDvqBsAp` | 200 | `req_v2THla5cj56kjrGTM` |
| 2026-10-29T08:46:47.675804+00:00 | GET | `acct_1UTK7rJnhDvqBsAp` | `/v1/balance` | 200 | `req_4m8xMVN79BKjLa` |
| 2026-10-29T08:46:48.048948+00:00 | GET | `acct_1UTK7rJnhDvqBsAp` | `/v1/balance_settings` | 200 | `req_SzEQ5bsy4n0Pgh` |
| 2026-10-29T08:46:48.390404+00:00 | POST | `acct_1UQNErJnhDgmiMAM` | `/v1/payment_intents` | 400 | `req_eCAlySPcWkYMAP` |
| 2026-10-29T08:47:35.517681+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/payment_intents` | 200 | `req_Nt5WMc4US6silY` |
| 2026-10-29T08:48:08.483121+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/balance` | 200 | `req_FTuck0Y99olfAW` |
| 2026-10-29T08:48:08.843282+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/account` | 200 | `req_TXkgWwVPGKMnbY` |
| 2026-10-29T08:48:09.241393+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v2/core/accounts/acct_1UTKTHJnhD0xjF0b` | 200 | `req_v2SEoFnaSPwh8KFwk` |
| 2026-10-29T08:48:09.723970+00:00 | GET | `acct_1UTKTHJnhD0xjF0b` | `/v1/balance` | 200 | `req_DYX8dWmztvesED` |
| 2026-10-29T08:48:10.117365+00:00 | GET | `acct_1UTKTHJnhD0xjF0b` | `/v1/balance_settings` | 200 | `req_NnQNcGF8Wrd1WU` |
| 2026-10-29T08:48:10.469192+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v2/core/accounts/acct_1UTK7rJnhDvqBsAp` | 200 | `req_v2O8CdQvmAmU7JnmC` |
| 2026-10-29T08:48:10.913095+00:00 | GET | `acct_1UTK7rJnhDvqBsAp` | `/v1/balance` | 200 | `req_bgcmxlJboC4iIT` |
| 2026-10-29T08:48:11.313213+00:00 | GET | `acct_1UTK7rJnhDvqBsAp` | `/v1/balance_settings` | 200 | `req_QR8r3HvZPPXH8M` |
| 2026-10-29T08:48:11.668598+00:00 | POST | `acct_1UQNErJnhDgmiMAM` | `/v1/payment_intents` | 200 | `req_XsbRHuUaJ0RIi3` |
| 2026-10-29T08:48:12.784958+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/payment_intents/pi_3UVp0JJnhDgmiMAM1jqIMm5A` | 200 | `req_1hIjRO2qNDvEdT` |
| 2026-10-29T08:48:13.108317+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/charges/ch_3UVp0JJnhDgmiMAM1IDZUMUL` | 200 | `req_9jXw9CW0pElyST` |
| 2026-10-29T08:48:13.422077+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/balance_transactions/txn_3UVp0JJnhDgmiMAM1kNioTjT` | 200 | `req_JLfgxBwg9lYQlk` |
| 2026-10-29T08:48:13.702545+00:00 | POST | `acct_1UQNErJnhDgmiMAM` | `/v1/transfers` | 200 | `req_xgoZHjh0uWiRP7` |
| 2026-10-29T08:48:14.559766+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/transfers/tr_3UVp0JJnhDgmiMAM1KFb8sQO` | 200 | `req_biwWM8ceSrqAsa` |
| 2026-10-29T08:48:14.828791+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/balance_transactions/txn_3UVp0JJnhDgmiMAM1W8oLUIF` | 200 | `req_J0N64gtFQu61MT` |
| 2026-10-29T08:48:15.114302+00:00 | GET | `acct_1UTKTHJnhD0xjF0b` | `/v1/charges/py_1UVp0MJnhD0xjF0b85viWYIp` | 200 | `req_2R70kaz0JHKrkK` |
| 2026-10-29T08:48:15.430469+00:00 | GET | `acct_1UTKTHJnhD0xjF0b` | `/v1/balance_transactions/txn_1UVp0MJnhD0xjF0buWrBb5S7` | 200 | `req_BjpQrbWDouJDNf` |
| 2026-10-29T08:48:15.723283+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/transfers/tr_3UVp0JJnhDgmiMAM1KFb8sQO/reversals` | 200 | `req_nAlapqA6tfIzDg` |
| 2026-10-29T08:48:16.001435+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/transfers` | 200 | `req_IZlAZuUC85KUES` |
| 2026-10-29T08:48:16.282443+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/balance_transactions` | 200 | `req_QLNcdZfNHhvfT9` |
| 2026-10-29T08:48:16.605190+00:00 | POST | `acct_1UQNErJnhDgmiMAM` | `/v1/payment_intents` | 200 | `req_8sz4MrH1VOsEUK` |
| 2026-10-29T08:48:17.931952+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/payment_intents/pi_3UVp0OJnhDgmiMAM1HD1rpdp` | 200 | `req_1lqzzGLhFfPy2T` |
| 2026-10-29T08:48:18.246235+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/charges/ch_3UVp0OJnhDgmiMAM1zFeNZFK` | 200 | `req_vs4GAhpN66jwb3` |
| 2026-10-29T08:48:18.638767+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/balance_transactions/txn_3UVp0OJnhDgmiMAM1zMKF70t` | 200 | `req_LnNqwNSK1duUtR` |
| 2026-10-29T08:48:18.953016+00:00 | POST | `acct_1UQNErJnhDgmiMAM` | `/v1/transfers` | 200 | `req_7HI4YQ73RlYAU6` |
| 2026-10-29T08:48:19.856021+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/transfers/tr_3UVp0OJnhDgmiMAM1PPJI308` | 200 | `req_BueAxFoMgf7CDS` |
| 2026-10-29T08:48:20.160640+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/balance_transactions/txn_3UVp0OJnhDgmiMAM1SqlQ7im` | 200 | `req_tFztBGyapdkiZW` |
| 2026-10-29T08:48:20.446782+00:00 | GET | `acct_1UTKTHJnhD0xjF0b` | `/v1/charges/py_1UVp0RJnhD0xjF0bqzE5TT73` | 200 | `req_VxKieG229UYDcN` |
| 2026-10-29T08:48:20.778304+00:00 | GET | `acct_1UTKTHJnhD0xjF0b` | `/v1/balance_transactions/txn_1UVp0RJnhD0xjF0bmidV0bX3` | 200 | `req_L0nwxxCdLuJj9C` |
| 2026-10-29T08:48:21.087327+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/transfers/tr_3UVp0OJnhDgmiMAM1PPJI308/reversals` | 200 | `req_YzdNwilPjCTwh6` |
| 2026-10-29T08:48:21.335319+00:00 | POST | `acct_1UQNErJnhDgmiMAM` | `/v1/transfers` | 200 | `req_sFgrOZ7GKIiIn2` |
| 2026-10-29T08:48:27.421260+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/transfers/tr_3UVp0OJnhDgmiMAM1e9upw7h` | 200 | `req_MWDHmZqydJZqsG` |
| 2026-10-29T08:48:27.692171+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/balance_transactions/txn_3UVp0OJnhDgmiMAM1jfmZKJb` | 200 | `req_28Tj1znZiUgzB8` |
| 2026-10-29T08:48:27.943705+00:00 | GET | `acct_1UTK7rJnhDvqBsAp` | `/v1/charges/py_1UVp0YJnhDvqBsApO2IqEPKM` | 200 | `req_Ju9SUubQYxUkQD` |
| 2026-10-29T08:48:28.260850+00:00 | GET | `acct_1UTK7rJnhDvqBsAp` | `/v1/balance_transactions/txn_1UVp0ZJnhDvqBsApXjdOtPKx` | 200 | `req_VDp7NpOR1yHRx3` |
| 2026-10-29T08:48:28.583985+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/transfers/tr_3UVp0OJnhDgmiMAM1e9upw7h/reversals` | 200 | `req_zAcZaSCkcIcaMj` |
| 2026-10-29T08:48:28.874369+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/transfers` | 200 | `req_bPMI2rkBLUv2TM` |
| 2026-10-29T08:48:29.150286+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/balance_transactions` | 200 | `req_8vVCLIME71IkmS` |
| 2026-10-29T08:48:29.432431+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/application_fees` | 200 | `req_8CdJmQ0RK7lGPH` |
| 2026-10-29T08:48:29.752932+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/balance` | 200 | `req_S8J8F0RDUUFSUh` |
| 2026-10-29T08:48:30.059879+00:00 | GET | `acct_1UTKTHJnhD0xjF0b` | `/v1/balance` | 200 | `req_lzZyLMdFTTMB8u` |
| 2026-10-29T08:48:30.477185+00:00 | GET | `acct_1UTK7rJnhDvqBsAp` | `/v1/balance` | 200 | `req_pTchfUsnbvMRjq` |
| 2026-10-29T08:49:11.540841+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/payment_intents` | 200 | `req_8V82FIWYdvhwwn` |
| 2026-10-29T08:49:12.656093+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/charges` | 200 | `req_A5d3d4noqr7odg` |
| 2026-10-29T08:49:13.246889+00:00 | GET | `acct_1UQNErJnhDgmiMAM` | `/v1/charges` | 200 | `req_xhGckh8PtaSkva` |

Alle erfolgreichen schreibenden Aufrufe betreffen ausschließlich
`POST /v1/payment_intents` und `POST /v1/transfers`. Es wurden keine
Refunds, Transfer-Reversals, Payouts, Kunden, Connect-Konten, Webhook-Endpunkte
oder Konfigurationen erstellt oder geändert. Die beiden Zahlungen und drei
Transfers bleiben als Sandbox-Nachweise erhalten; es wurde keine automatische
Rückabwicklung ausgeführt. Historische Zahlungsobjekte wurden nicht verändert.

## Kurze Zusammenfassung für Stripe

Wir haben im Gratilo-Testplattformkonto `acct_1UQNErJnhDgmiMAM` zwei neue
isolierte SCT-Vergleichsfälle mit `pm_card_visa` ausgeführt, je 25,63 EUR
Gesamtbelastung. Ein Fall transferiert 25,00 EUR an einen Empfänger, der zweite
je 12,50 EUR an zwei Empfänger. In beiden Fällen weist die Zahlungsbuchung
exakt 1,06 EUR `stripe_fee` und 24,57 EUR netto aus. Alle Empfänger-Balance-
Transactions sind lesbar und weisen die zugesagten Nettobeträge ohne zusätzliche
Gebühr aus, derzeit `pending`. Die Plattform trägt jeweils 0,43 EUR Differenz.
Die IDs und Request-IDs sind oben vollständig nach Kontokontext getrennt aufgeführt.

Bitte bestätigen Sie, welche weiteren Connect-/Konto-/Payout-Kosten außerhalb
dieser Zahlungs- und Transferbuchungen anfallen können und unter welchen
vereinbarten Bedingungen SCT für Einzel- und Teamtrinkgelder mit festem
Empfängernetto geeignet ist. Kontokonfiguration, Konditionen und Live-Zulassung
für Arbeitnehmer ohne Gewerbe bleiben offene Abstimmungspunkte.
