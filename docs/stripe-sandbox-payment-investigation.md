# Gratilo: historische Stripe-Sandboxzahlung über 25,63 EUR

## A. Zusammenfassung

| Frage | Ergebnis |
|---|---|
| Zahlung gefunden | **Ja: ein eindeutig zugeordneter Kandidat in den erfolgreich durchsuchten Kontokontexten** |
| Gebühr von 1,06 EUR bestätigt | **Ja: `fee=106`, eine einzelne `stripe_fee`-Position über 106 Cent** |
| Vollständige Transaktionskette vorhanden | **Teilweise: Checkout → PaymentIntent → Charge → Zahlungsbuchung → ein Transfer → Plattform-Transferbuchung belegt; Empfängerbuchung nicht lesbar** |
| Verifiziertes Gratilo-Testplattformkonto | `acct_1UQNErJnhDgmiMAM` |
| Separates lokales Testplattformkonto | `acct_1UFbtVCMj7Vzvm34`; keine passende Zahlung, nicht mit Gratilo-Laufzeitkonto vermischt |
| Zahlung UTC | **2026-10-20 13:42:24 UTC** |
| Fortgesetzte API-Untersuchung | ab 2026-10-29T08:17:05.738530+00:00 |
| Berichtsstand | 2026-10-29 08:25:32 UTC |
| Schreibende Stripe-Aufrufe / Infrastrukturänderungen | **Keine** |

Die Gebühr von **1,06 EUR ist jetzt unmittelbar durch Stripe-API-Daten bestätigt**.
Die Charge belastete 25,63 EUR; die zugehörige Zahlungs-Balance-Transaction
weist 24,57 EUR netto für die Plattform aus. `2563 − 106 = 2457` ist erfüllt.
Es handelt sich um **Separate Charges and Transfers (SCT)**, nicht um eine Direct
Charge und nicht um eine Destination Charge.

**Verteilungsgrenze:** Es ist aktuell genau ein verknüpfter Transfer über
**12,50 EUR** nachweisbar. Die vollständige Plattform-Transferliste (50 Objekte)
und der zusätzliche Transfergruppen-Abruf stimmen darin überein. Das Zielkonto
`acct_1USFInJnhDfTGhs9` ist nach der Accounts-v2-API **geschlossen** (`closed=true`).
Seine `destination_payment`-ID ist am Transfer vorhanden, die Empfängerzahlung
und deren Balance Transaction sind wegen HTTP 403 / `account_invalid` nicht lesbar.
Eine zweite Übertragung oder eine vollständige Empfängergutschrift über 25,00 EUR
wird deshalb ausdrücklich **nicht** behauptet.

Dieser Bericht ersetzt den anfänglichen Negativbefund: Nach der vom Nutzer
erneuerten AWS-SSO-Anmeldung war der separat hinterlegte Testzugang verfügbar.
Die ursprüngliche lokale Suche fand im anderen Plattformkonto statt. Der jetzige
positive Befund beruht auf neuen Stripe-GET-Antworten im richtigen Kontokontext.

## B. Zugänge, Suche und Zahlungsdaten

### B.1 Tatsächlich verfügbare Zugänge und Rechte

| Zugang | Quelle ohne Schlüsselwert | Per API bestätigtes Plattformkonto | Ergebnis |
|---|---|---|---|
| `access-1` | `.env`, `.env.stripe-test`; derselbe Schlüssel in beiden Dateien | `acct_1UFbtVCMj7Vzvm34` | Testmodus bestätigt; keine passende Zahlung |
| `access-2` | AWS Secrets Manager, `gratilo/prd/stripe-test`, Feld `secret_key`; AWS-Profil `gratilo`, Region `eu-central-1` | `acct_1UQNErJnhDgmiMAM` | Testmodus und Gratilo-Bezeichnung bestätigt; gesuchte Zahlung gefunden |

Beide Zugänge sind unterschiedliche Stripe-Test-Secret-Keys. `GET /v1/balance`
lieferte jeweils `livemode=false`; `GET /v1/account` belegte die jeweilige ID.
Der Secret-Abruf erfolgte lesend; dessen Rohantwort wurde nur im Prozessspeicher
verarbeitet. Schlüssel wurden nicht in Kommandoargumenten, Protokollen oder
Berichten ausgegeben. Auch personenbezogene Zahlungsdaten und vollständige
ungeschützte API-Dumps wurden nicht gespeichert.

Für **beide Plattformkonten** lieferten die Listen-Endpunkte für PaymentIntents,
Charges, Balance Transactions, Transfers, Connect-Konten und Application Fees
HTTP 200. Dies belegt die tatsächlich benötigten Listen-Leserechte, keine
Schreibberechtigung oder vollständige Berechtigungsmatrix eines Schlüssels.

Stripe- und AWS-MCP-Tools waren in dieser Sitzung nicht verfügbar. Die
installierte Stripe CLI nennt in ihrer Konfiguration das lokale Konto
`acct_1UFbtVCMj7Vzvm34`, enthält aber keinen zusätzlichen gespeicherten API-Key.
Publishable-Test-Keys gewähren keine serverseitigen Finanzleserechte und wurden
hierfür nicht verwendet. Es erfolgte kein Stripe-CLI-Login, keine neue
Kontoverbindung und keine Änderung an Konten oder Berechtigungen.

### B.2 Vollständigkeit der Suche und Kandidaten

Gesucht wurde ohne Datums- oder Statusbeschränkung nach exakt `amount=2563`,
`currency=eur`, `livemode=false`. Die REST-Listen wurden mit `limit=100`
abgerufen und ihre vollständige Pagination geprüft. Insgesamt wurden
**72 PaymentIntents und 123 Charges in 16 Kontokontexten** gelesen.
Alle folgenden Zahlungslisten endeten mit `has_more=false`. Die passenden
PaymentIntent- und Charge-Objekte sind durch gegenseitige Referenzen dieselbe
Zahlung, keine zwei Kandidaten.

| Verifiziertes Plattformkonto | Kontokontext | PaymentIntents | Charges | Exakte Treffer PI / Charge |
|---|---|---:|---:|---:|
| `acct_1UFbtVCMj7Vzvm34` | `acct_1UFbtVCMj7Vzvm34` | 30 | 31 | 0 / 0 |
| `acct_1UFbtVCMj7Vzvm34` | `acct_1UJFmGCMj7iPU5TL` | 0 | 3 | 0 / 0 |
| `acct_1UFbtVCMj7Vzvm34` | `acct_1UJ6vuCMj7Y2Rkwp` | 0 | 10 | 0 / 0 |
| `acct_1UFbtVCMj7Vzvm34` | `acct_1UGFJ3CMj7h4O2B5` | 0 | 8 | 0 / 0 |
| `acct_1UFbtVCMj7Vzvm34` | `acct_1UFwWXCMj7gOB1JT` | 0 | 3 | 0 / 0 |
| `acct_1UFbtVCMj7Vzvm34` | `acct_1UFvukCMj7F16Uaj` | 0 | 5 | 0 / 0 |
| `acct_1UFbtVCMj7Vzvm34` | `acct_1UFtAMCMj7As9BMp` | 0 | 0 | 0 / 0 |
| `acct_1UFbtVCMj7Vzvm34` | `acct_1UFtALCMj7yzAyy1` | 1 | 1 | 0 / 0 |
| `acct_1UQNErJnhDgmiMAM` | `acct_1UQNErJnhDgmiMAM` | 32 | 31 | 1 / 1 |
| `acct_1UQNErJnhDgmiMAM` | `acct_1UU8WZJnhDaUD8Tq` | 0 | 0 | 0 / 0 |
| `acct_1UQNErJnhDgmiMAM` | `acct_1UU8NqJnhDRQ22zQ` | 0 | 0 | 0 / 0 |
| `acct_1UQNErJnhDgmiMAM` | `acct_1UTkpsJnhDryoncr` | 0 | 0 | 0 / 0 |
| `acct_1UQNErJnhDgmiMAM` | `acct_1UTkHeJnhDXaxfBd` | 1 | 1 | 0 / 0 |
| `acct_1UQNErJnhDgmiMAM` | `acct_1UTKTHJnhD0xjF0b` | 0 | 11 | 0 / 0 |
| `acct_1UQNErJnhDgmiMAM` | `acct_1UTK7rJnhDvqBsAp` | 7 | 18 | 0 / 0 |
| `acct_1UQNErJnhDgmiMAM` | `acct_1UTIOlJnhD6sosYk` | 1 | 1 | 0 / 0 |

Die Connect-Kontolisten beider Plattformen enthielten jeweils sieben aktuelle
Konten, ebenfalls `has_more=false`. Historische geschlossene Konten sind dadurch
nicht pauschal ausgeschlossen: Die aus dem Repository bekannten Konten
`acct_1USFInJnhDfTGhs9` und `acct_1USZOlJnhDeaeFtd` wurden zusätzlich gezielt
geprüft. Accounts v2 bestätigt für beide `livemode=false`, `closed=true`.
V1-PaymentIntent-/Charge-Listen sind dort HTTP 403 / `account_invalid`.
Eine globale Ausschlussaussage über weitere Zahlungen in diesen nicht mehr
lesbaren Kontexten ist nicht möglich; der gefundene Kandidat ist jedoch durch
Betrag **und** die historische interne Gratilo-ID eindeutig belegt.

| Kandidat | Konto | Datum UTC | PaymentIntent | Charge | Zuordnung |
|---|---|---|---|---|---|
| 1 | `acct_1UQNErJnhDgmiMAM` | 2026-10-20 13:42:24 UTC | `pi_3USdJ6JnhDgmiMAM1JWxToC5` | `ch_3USdJ6JnhDgmiMAM1hgP48Kw` | `metadata.transaction_id=bdb0617a-af1f-4724-b9f1-c8e0dfaf40e2` an PI, Charge und Transfer |

### B.3 Nachgewiesene Zahlungsobjekte

| Feld | Stripe-API-Wert |
|---|---|
| Plattform / Zahlungskontext | `acct_1UQNErJnhDgmiMAM`; v1-Aufrufe ohne `Stripe-Account`-Header |
| Checkout Session | `cs_test_a1lKCTUg8K0O5LhuJDBMolkw9Iz5lNnHdPICTu82UNWGkTb7yd8rDLW0A0` |
| Checkout erstellt | 2026-10-20 13:40:56 UTC |
| Checkout-Status | `status=complete`, `payment_status=paid`, `mode=payment`, `livemode=false` |
| Checkout-Gesamtbetrag | `amount_total=2563`, `currency=eur` |
| PaymentIntent | `pi_3USdJ6JnhDgmiMAM1JWxToC5` |
| PI-Betrag / erhalten | `amount=2563`, `amount_received=2563`, `currency=eur` |
| PI-Status / erstellt | `status=succeeded`, `livemode=false`; 2026-10-20 13:42:24 UTC |
| Charge | `ch_3USdJ6JnhDgmiMAM1hgP48Kw` |
| Charge-Betrag | `amount=2563`, `amount_captured=2563`, `currency=eur` |
| Charge-Status / erstellt | `status=succeeded`, `paid=true`, `captured=true`, `livemode=false`; 2026-10-20 13:42:24 UTC |
| Erstattungsstand der Charge | `amount_refunded=0`, `refunded=false` |
| Zahlungs-Balance-Transaction | `txn_3USdJ6JnhDgmiMAM1BlZMYI9` |
| Transfergruppe | `gratilo_bdb0617a-af1f-4724-b9f1-c8e0dfaf40e2` |
| Interne Gratilo-Transaktion | `bdb0617a-af1f-4724-b9f1-c8e0dfaf40e2` |
| Pool-ID aus Stripe-Metadaten | `3709f0f2-4e62-4327-bd70-e957f00967e9` |

Die Checkout-Metadaten enthalten `fee_covered="true"` und
`fee_surcharge_minor="63"`. Dieser historische Aufschlag ist damit in Stripe
belegt und nicht nur aus einer aktuellen Tarifformel geschätzt. Aus
`2563 − 63 = 2500` lässt sich ein Basisbetrag von 25,00 EUR ableiten; dies ist
als Rechenergebnis ausgewiesen, nicht als zusätzlich vorhandenes Stripe-Feld.

### B.4 Sämtliche derzeit zugeordneten Transfers

| Feld | Nachgewiesener Wert |
|---|---|
| Transfer | `tr_3USdJ6JnhDgmiMAM1Fh8JP66` |
| Betrag / Währung | `amount=1250`, `currency=eur` = **12,50 EUR** |
| Erstellt UTC | 2026-10-20 13:42:31 UTC |
| Testmodus | `livemode=false` |
| `source_transaction` | `ch_3USdJ6JnhDgmiMAM1hgP48Kw` |
| `transfer_group` | `gratilo_bdb0617a-af1f-4724-b9f1-c8e0dfaf40e2` |
| `destination` | `acct_1USFInJnhDfTGhs9` |
| `destination_payment` | `py_1USdJCJnhDfTGhs9nq4LKHMc` |
| Plattform-Transfer-Balance-Transaction | `txn_3USdJ6JnhDgmiMAM1lK4DhQS` |
| Rückübertragungsstand | `amount_reversed=0`, `reversed=false`; Reversal-Liste leer und vollständig |
| Empfänger-Balance-Transaction | **Nicht ermittelt: Empfängerzahlung nicht lesbar** |

Ein allgemeines Feld „Transferstatus erfolgreich“ wurde nicht erfunden. Die
vorhandenen Objekt- und Rückübertragungsfelder sowie die tatsächlich gebuchte
Plattform-Balance-Transaction sind oben angegeben. Die Übertragung zu einem
Connect-Konto ist nicht mit einem Bank-Payout gleichzusetzen.

`GET /v1/transfers?limit=100` lieferte 50 Objekte mit `has_more=false`.
Die Zuordnung prüfte explizite Charge-Referenzen, `source_transaction` und
`transfer_group`; genau der oben genannte Transfer ist zugeordnet.
Der zusätzlich nach der exakten Gruppe gefilterte Abruf lieferte genau diesen
einen Transfer mit `has_more=false`. Es wird kein zweiter Transfer aus einem
anderen Zahlungsvorgang, einem ähnlichen Betrag oder einem Planwert ergänzt.

## C. Vollständige belegte Gebührenanalyse

### C.1 Zahlungs-Balance-Transaction auf der Plattform

| Feld | API-Wert | EUR, soweit Geldbetrag |
|---|---|---:|
| `id` | `txn_3USdJ6JnhDgmiMAM1BlZMYI9` | — |
| `source` | `ch_3USdJ6JnhDgmiMAM1hgP48Kw` | — |
| `amount` | `2563` Cent | 25,63 |
| `fee` | `106` Cent | **1,06** |
| `net` | `2457` Cent | **24,57** |
| `currency` | `eur` | — |
| `type` | `charge` | — |
| `reporting_category` | `charge` | — |
| `status` | `available` | — |
| `created` / `available_on` | `1792503744` / `1792503744` = 2026-10-20 13:42:24 UTC | — |
| `exchange_rate` | `null` | — |

Vollständige `fee_details` dieser Zahlungsbuchung, unverändert hinsichtlich
aller gelieferten Gebührenfelder:

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

Es gibt **eine** Gebührenposition: 106 Cent `stripe_fee`, Beschreibung
`Stripe processing fees`, Währung EUR, `application=null`.
Die Summe der Gebührenpositionen entspricht `fee=106`.
Die Centprüfung ist erfüllt: **`2563 − 106 = 2457`**.
Die 1,06 EUR sind somit nicht die Summe eines Transfers und einer Gebühr und
nicht bloß ein geschätzter oder zusätzlich eingezogener Gästeaufschlag.

### C.2 Application Fees, Transferkosten und weitere Kosten

- `payment_intent.application_fee_amount=null`.
- `charge.application_fee=null`, `charge.application_fee_amount=null`.
- Die vollständige Plattformliste der Application Fees enthielt 13 Objekte
  (`has_more=false`); keines verweist auf diese Charge. Gebühren anderer
  Zahlungen wurden nicht übernommen.
- `GET /v1/balance_transactions?source=ch_3USdJ6JnhDgmiMAM1hgP48Kw&limit=100`
  lieferte vollständig genau die oben ausgewiesene Zahlungsbuchung.
- Die Plattformbuchung des zugeordneten Transfers hat `amount=-1250`, `fee=0`,
  `net=-1250`, `fee_details=[]`, `currency=eur`, `type=transfer`,
  `reporting_category=transfer`, `status=available` und
  `source=tr_3USdJ6JnhDgmiMAM1Fh8JP66`. Ihre Rechnung `−1250 − 0 = −1250` ist erfüllt.
- Für diese gelesenen Zahlungs- und Plattform-Transferbuchungen ist keine
  weitere Gebührenposition ausgewiesen. Das bedeutet **nicht**, dass damit
  sämtliche periodischen Connect-Konto-, Payout- oder Vertragskosten ausgeschlossen
  wären. Solche späteren oder aggregierten Kosten wurden dieser Zahlung nicht
  ohne Beleg zugeschlagen.
- Die geschlossene Empfängerseite bleibt unlesbar. Eine dortige zusätzliche
  Gebühr, die Empfänger-Balance-Transaction und ein Bank-Payout sind hier nicht
  nachgewiesen. Der Transferbetrag von 12,50 EUR wird nicht ohne diesen Nachweis
  als endgültiger Bank-Nettozufluss bezeichnet.

### C.3 Aufschlag, Gebührendifferenz und tatsächlicher Plattformrest

| Rechnung | Ergebnis | Bedeutung |
|---|---:|---|
| `2563 − 106` | 2457 Cent / 24,57 EUR | Tatsächliches Netto der Zahlungsbuchung |
| `106 − 63` | 43 Cent / 0,43 EUR | Tatsächliche Stripe-Gebühr übersteigt den in Checkout-Metadaten belegten Aufschlag |
| `2457 − 1250` | **1207 Cent / 12,07 EUR** | Saldo der nachgewiesenen Zahlungs- und Transferbuchungen auf der Plattform |
| `2457 − 2500` | −43 Cent / −0,43 EUR | **Nur bedingte Rechnung**, falls insgesamt 25,00 EUR transferiert werden sollten; nicht als tatsächlich abgeschlossene Verteilung belegt |

Die 12,07 EUR sind **kein nachgewiesener Plattformgewinn und keine zusätzliche
Stripe-Gebühr**. Es ist der Saldo der konkret zugeordneten, gelesenen
Plattformbuchungen. Ob weitere geplante Allokationen offen, verworfen oder
anderweitig behandelt wurden, lässt sich aus den gelesenen Stripe-Objekten nicht
feststellen. Die historische Repository-Aussage „43 Cent Unterdeckung“ ist als
Differenz von Gebühr und Aufschlag nachvollziehbar; eine tatsächliche vollständige
Übertragung von 25,00 EUR ist aktuell nicht nachgewiesen.

Ein historischer geschätzter Tarif oder die heutige Gratilo-Gebührenformel wurde
nicht zur Ermittlung der tatsächlichen Stripe-Gebühr verwendet. Individuelle
Stripe-/Connect-Vertragskonditionen werden aus diesem Sandboxbefund nicht abgeleitet.

## D. Technische Verknüpfung und Connect-Modell

| Beziehung | Tatsächlicher Stripe-Beleg |
|---|---|
| Checkout → PaymentIntent | `session.payment_intent=pi_3USdJ6JnhDgmiMAM1JWxToC5` |
| PaymentIntent → Charge | `pi.latest_charge=ch_3USdJ6JnhDgmiMAM1hgP48Kw` |
| Charge → PaymentIntent | `charge.payment_intent=pi_3USdJ6JnhDgmiMAM1JWxToC5`; vollständige Charge-Liste für diesen PI enthält genau eine Charge |
| Charge → Zahlungsbuchung | `charge.balance_transaction=txn_3USdJ6JnhDgmiMAM1BlZMYI9` |
| Zahlungsbuchung → Charge | `balance_transaction.source=ch_3USdJ6JnhDgmiMAM1hgP48Kw` |
| Charge → Transfer | `transfer.source_transaction=ch_3USdJ6JnhDgmiMAM1hgP48Kw`; zusätzliche identische Transfergruppe und interne Transaktionsmetadaten |
| Transfer → Zielkonto | `transfer.destination=acct_1USFInJnhDfTGhs9` |
| Transfer → Empfängerzahlung | `transfer.destination_payment=py_1USdJCJnhDfTGhs9nq4LKHMc`; ID belegt, Objektabruf HTTP 403 |
| Transfer → Plattformbuchung | `transfer.balance_transaction=txn_3USdJ6JnhDgmiMAM1lK4DhQS` |
| Plattformbuchung → Transfer | `balance_transaction.source=tr_3USdJ6JnhDgmiMAM1Fh8JP66` |
| Historischer Projektvorgang → Stripe-Kette | Identische `metadata.transaction_id=bdb0617a-af1f-4724-b9f1-c8e0dfaf40e2` an PI, Charge und Transfer; gleiche Checkout-Metadaten |
| Zielkonto geschlossen | `GET /v2/core/accounts/acct_1USFInJnhDfTGhs9`: passende `id`, `livemode=false`, `closed=true` |

Die Zahlung liegt auf der Plattform. `payment_intent.transfer_data=null`,
`charge.transfer_data=null`, `charge.transfer=null`, `on_behalf_of=null`;
zugleich existiert der separate Transfer mit exakter Source-Charge-Verknüpfung.
Das belegt **SCT**. Eine Direct Charge wäre im Connected-Account-Kontext zu
untersuchen; diese Zahlung wurde unabhängig davon auf der Plattform verifiziert.
Eine Destination Charge ist durch die tatsächlichen Transfer-/Charge-Felder
hier nicht belegt.

Semantische Referenzen: [Stripe-Connect-Zahlungsmodelle](https://docs.stripe.com/connect/charges),
[BalanceTransaction](https://docs.stripe.com/api/balance_transactions/object),
[Transfer-Objekt](https://docs.stripe.com/api/transfers/object).
Die Dokumentation erklärt die Feldbedeutung; die oben genannten konkreten Werte
stammen aus den tatsächlich ausgeführten API-GETs.

## E. API-Nachweise und konkrete Zugriffslücke

### E.1 Verwendete Endpunkte

Ausschließlich GET, keine POST-, DELETE- oder Konfigurationsaufrufe:

- `/v1/balance`, `/v1/account`, `/v1/accounts`.
- `/v1/payment_intents` und `/v1/payment_intents/pi_3USdJ6JnhDgmiMAM1JWxToC5`.
- `/v1/charges`, einschließlich Filter `payment_intent`, sowie
  `/v1/charges/ch_3USdJ6JnhDgmiMAM1hgP48Kw`.
- `/v1/checkout/sessions` mit Filter `payment_intent` und Abruf der konkreten Session.
- `/v1/balance_transactions/txn_3USdJ6JnhDgmiMAM1BlZMYI9` und Source-gefilterte Liste.
- `/v1/transfers`, zusätzliche Filterung nach der exakten `transfer_group`,
  `/v1/transfers/tr_3USdJ6JnhDgmiMAM1Fh8JP66` und dessen `/reversals`.
- `/v1/balance_transactions/txn_3USdJ6JnhDgmiMAM1lK4DhQS`.
- `/v1/application_fees`, vollständig paginiert.
- `/v1/charges/py_1USdJCJnhDfTGhs9nq4LKHMc` mit
  `Stripe-Account: acct_1USFInJnhDfTGhs9`: **HTTP 403, `account_invalid`**.
- `/v1/balance`, `/v1/payment_intents`, `/v1/charges` im geschlossenen
  Zielkontokontext: **HTTP 403, `account_invalid`**.
- `/v1/accounts/acct_1USFInJnhDfTGhs9`: **HTTP 403, `account_invalid`**.
- `/v2/core/accounts/acct_1USFInJnhDfTGhs9` mit
  `Stripe-Version: 2026-09-30.endive`: HTTP 200, `closed=true`, `livemode=false`.
  Dasselbe gilt für das zusätzlich geprüfte historische Konto
  `acct_1USZOlJnhDeaeFtd`; diesem wurde keine Zahlung oder Übertragung hinzugedichtet.

V1-Anfragen verwendeten keinen expliziten API-Versionsoverride. Die Plattform
wurde jeweils allein durch ihren eigenen Testschlüssel bestimmt. Der
`Stripe-Account`-Header wurde nur für die ausdrücklich benannten Connect-Kontexte
verwendet. Für die V2-Leseabrufe wurde die oben dokumentierte Version gesetzt.

### E.2 Dashboard

[PaymentIntent im Stripe-Test-Dashboard](https://dashboard.stripe.com/test/payments/pi_3USdJ6JnhDgmiMAM1JWxToC5).
Dafür muss im Dashboard **acct_1UQNErJnhDgmiMAM** und der korrekte Test-/Sandbox-Kontext
gewählt sein. Der Link ersetzt keinen API-Beleg. Für die geschlossene
Empfängerseite wird kein funktionierender Dashboardzugang behauptet.

### E.3 Fehlende Daten und erforderliche Klärung mit Stripe

1. Die Empfängerzahlung `py_1USdJCJnhDfTGhs9nq4LKHMc` und ihre
   Balance-Transaction-ID sind **nicht lesbar**. Das zugehörige Konto ist
   `closed=true`; ein normaler V1-Abruf unter dem Plattform-Testschlüssel
   liefert `account_invalid`. Benötigt wird eine historische Auskunft/Export
   von Stripe beziehungsweise ein von Stripe unterstützter lesender Zugriff
   auf die Buchungen dieses geschlossenen Testkontos. Es wurde weder versucht,
   das Konto wieder zu öffnen, noch seine Konfiguration zu ändern.
2. Es wurde nur ein Transfer über 12,50 EUR gefunden. Stripe sollte bestätigen,
   ob zur Source-Charge oder Gruppe zusätzliche historische, nicht über die
   jetzigen Listen sichtbare Transfers/Empfängerbuchungen existieren. Bis zu
   einem solchen Beleg bleibt ein zweiter Transfer **nicht ermittelt**.
3. Falls Gratilo eine vollständige Empfängerverteilung von 25,00 EUR vorgesehen
   hatte, ist ergänzend die damalige interne Allokation samt Fehler-/Statushistorie
   des Gratilo-Datensatzes zu prüfen. Die historische Datenbank wurde hierfür
   nicht abgefragt oder verändert; Planwerte sind keine Stripe-Transfers.
4. Separat oder periodisch berechnete Connect-/Payout-Kosten bedürfen ihrer
   eigenen Rechnungs-/Buchungsbelege. Sie wurden nicht mit der einzelnen
   `stripe_fee=106` vermischt. Ein tatsächlicher Bank-Payout wurde nicht untersucht.
5. Der frühere AWS-SSO-Blocker ist behoben. Der Test-Secret-Abruf sowie die
   benötigten Plattform-Leserechte funktionieren; derzeit fehlt kein weiterer
   Plattformschlüssel. Die verbleibende Lücke betrifft die geschlossene Empfängerseite.

### E.4 Nachweisprotokoll mit Stripe Request-IDs

Nur gefilterte Felder; keine vollständigen API-Dumps. Kontokontext „Plattform“
ist nachfolgend bereits in die konkrete Account-ID des jeweiligen Schlüssels
aufgelöst. Nicht gelieferte Request-IDs werden nicht erfunden.

| UTC laut API-Antwort | Kontokontext | GET-Endpunkt / Parameter | HTTP | Stripe Request-ID |
|---|---|---|---:|---|
| Thu, 29 Oct 2026 08:17:06 GMT | `acct_1UFbtVCMj7Vzvm34` | `/v1/balance` | 200 | `req_XdgwUGkRZTZIMG` |
| Thu, 29 Oct 2026 08:17:07 GMT | `acct_1UFbtVCMj7Vzvm34` | `/v1/account` | 200 | `req_R1TufnZQA4HVgW` |
| Thu, 29 Oct 2026 08:17:08 GMT | `acct_1UFbtVCMj7Vzvm34` | `/v1/payment_intents · limit=1` | 200 | `req_SHGj7mqCOk6tWu` |
| Thu, 29 Oct 2026 08:17:10 GMT | `acct_1UFbtVCMj7Vzvm34` | `/v1/charges · limit=1` | 200 | `req_DmWveL53BhSVYW` |
| Thu, 29 Oct 2026 08:17:11 GMT | `acct_1UFbtVCMj7Vzvm34` | `/v1/balance_transactions · limit=1` | 200 | `req_CkHrlsbPF7jyMa` |
| Thu, 29 Oct 2026 08:17:11 GMT | `acct_1UFbtVCMj7Vzvm34` | `/v1/transfers · limit=1` | 200 | `req_jJkGdaQSEAk3LS` |
| Thu, 29 Oct 2026 08:17:13 GMT | `acct_1UFbtVCMj7Vzvm34` | `/v1/accounts · limit=1` | 200 | `req_1Nn7mzkBAeEgEA` |
| Thu, 29 Oct 2026 08:17:14 GMT | `acct_1UFbtVCMj7Vzvm34` | `/v1/application_fees · limit=1` | 200 | `req_wIkk4k2FuuqtEy` |
| Thu, 29 Oct 2026 08:17:15 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/balance` | 200 | `req_3XU6sKGeq6k7Mm` |
| Thu, 29 Oct 2026 08:17:17 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/account` | 200 | `req_zQDJ8n8tp6wOge` |
| Thu, 29 Oct 2026 08:17:18 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/payment_intents · limit=1` | 200 | `req_1PIl13GntkXRhN` |
| Thu, 29 Oct 2026 08:17:20 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/charges · limit=1` | 200 | `req_F3sR6H2P3I7xoM` |
| Thu, 29 Oct 2026 08:17:22 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/balance_transactions · limit=1` | 200 | `req_2OLxZfoXJphM3p` |
| Thu, 29 Oct 2026 08:17:23 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/transfers · limit=1` | 200 | `req_Qofup2LkoPyP7L` |
| Thu, 29 Oct 2026 08:17:24 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/accounts · limit=1` | 200 | `req_ddZoFIjYARVDrF` |
| Thu, 29 Oct 2026 08:17:25 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/application_fees · limit=1` | 200 | `req_Q9tkvKjj65tsf3` |
| Thu, 29 Oct 2026 08:17:36 GMT | `acct_1UFbtVCMj7Vzvm34` | `/v1/accounts · limit=100` | 200 | `req_QQ0zACvN0Ib6L3` |
| Thu, 29 Oct 2026 08:17:38 GMT | `acct_1UFbtVCMj7Vzvm34` | `/v1/payment_intents · limit=100` | 200 | `req_U1nzcgcWp1AcDQ` |
| Thu, 29 Oct 2026 08:17:40 GMT | `acct_1UFbtVCMj7Vzvm34` | `/v1/charges · limit=100` | 200 | `req_NiuYMLg6M3ECiV` |
| Thu, 29 Oct 2026 08:17:43 GMT | `acct_1UJFmGCMj7iPU5TL` | `/v1/balance` | 200 | `req_kO96W7CyNqh5ZG` |
| Thu, 29 Oct 2026 08:17:44 GMT | `acct_1UJFmGCMj7iPU5TL` | `/v1/payment_intents · limit=100` | 200 | `req_do9nqMhwynYEco` |
| Thu, 29 Oct 2026 08:17:45 GMT | `acct_1UJFmGCMj7iPU5TL` | `/v1/charges · limit=100` | 200 | `req_0pYUCzW0bhsEfb` |
| Thu, 29 Oct 2026 08:17:46 GMT | `acct_1UJ6vuCMj7Y2Rkwp` | `/v1/balance` | 200 | `req_W0hFcrL1p1OExm` |
| Thu, 29 Oct 2026 08:17:49 GMT | `acct_1UJ6vuCMj7Y2Rkwp` | `/v1/payment_intents · limit=100` | 200 | `req_eC99KuTGvJY1Al` |
| Thu, 29 Oct 2026 08:17:50 GMT | `acct_1UJ6vuCMj7Y2Rkwp` | `/v1/charges · limit=100` | 200 | `req_Pzkzu7wq9VYseJ` |
| Thu, 29 Oct 2026 08:17:52 GMT | `acct_1UGFJ3CMj7h4O2B5` | `/v1/balance` | 200 | `req_2rFA2AJGIQuZ5m` |
| Thu, 29 Oct 2026 08:17:54 GMT | `acct_1UGFJ3CMj7h4O2B5` | `/v1/payment_intents · limit=100` | 200 | `req_aR2Rvlig3jZwkP` |
| Thu, 29 Oct 2026 08:17:55 GMT | `acct_1UGFJ3CMj7h4O2B5` | `/v1/charges · limit=100` | 200 | `req_3F6joYARJPSqbh` |
| Thu, 29 Oct 2026 08:17:56 GMT | `acct_1UFwWXCMj7gOB1JT` | `/v1/balance` | 200 | `req_OVjnqzbLHX26yA` |
| Thu, 29 Oct 2026 08:17:57 GMT | `acct_1UFwWXCMj7gOB1JT` | `/v1/payment_intents · limit=100` | 200 | `req_TfuJqec6rW1dQv` |
| Thu, 29 Oct 2026 08:17:58 GMT | `acct_1UFwWXCMj7gOB1JT` | `/v1/charges · limit=100` | 200 | `req_RiH3qDSrkEFSZW` |
| Thu, 29 Oct 2026 08:17:59 GMT | `acct_1UFvukCMj7F16Uaj` | `/v1/balance` | 200 | `req_UGulUGFDkHWkC2` |
| Thu, 29 Oct 2026 08:18:00 GMT | `acct_1UFvukCMj7F16Uaj` | `/v1/payment_intents · limit=100` | 200 | `req_U7kCuO0SCzebhj` |
| Thu, 29 Oct 2026 08:18:02 GMT | `acct_1UFvukCMj7F16Uaj` | `/v1/charges · limit=100` | 200 | `req_cUdJNzr5vab33X` |
| Thu, 29 Oct 2026 08:18:03 GMT | `acct_1UFtAMCMj7As9BMp` | `/v1/balance` | 200 | `req_nX0kzYCZjw83Sw` |
| Thu, 29 Oct 2026 08:18:04 GMT | `acct_1UFtAMCMj7As9BMp` | `/v1/payment_intents · limit=100` | 200 | `req_DUjA7ApKBZShx7` |
| Thu, 29 Oct 2026 08:18:05 GMT | `acct_1UFtAMCMj7As9BMp` | `/v1/charges · limit=100` | 200 | `req_MvYDGbB9xIwDcA` |
| Thu, 29 Oct 2026 08:18:07 GMT | `acct_1UFtALCMj7yzAyy1` | `/v1/balance` | 200 | `req_xba1DwYt4MvuXR` |
| Thu, 29 Oct 2026 08:18:08 GMT | `acct_1UFtALCMj7yzAyy1` | `/v1/payment_intents · limit=100` | 200 | `req_taMWqQtTJPIfi9` |
| Thu, 29 Oct 2026 08:18:09 GMT | `acct_1UFtALCMj7yzAyy1` | `/v1/charges · limit=100` | 200 | `req_6wkwZklZRueESH` |
| Thu, 29 Oct 2026 08:18:12 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/accounts · limit=100` | 200 | `req_YsFe3GszKXVYiy` |
| Thu, 29 Oct 2026 08:18:13 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/payment_intents · limit=100` | 200 | `req_PDDfvpshbdubiA` |
| Thu, 29 Oct 2026 08:18:16 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/charges · limit=100` | 200 | `req_NxXs4xanRV9mMI` |
| Thu, 29 Oct 2026 08:18:20 GMT | `acct_1UU8WZJnhDaUD8Tq` | `/v1/balance` | 200 | `req_byFHNme1pKkXqC` |
| Thu, 29 Oct 2026 08:18:21 GMT | `acct_1UU8WZJnhDaUD8Tq` | `/v1/payment_intents · limit=100` | 200 | `req_jUtpAZWz7udM1Q` |
| Thu, 29 Oct 2026 08:18:22 GMT | `acct_1UU8WZJnhDaUD8Tq` | `/v1/charges · limit=100` | 200 | `req_W2C2ZtgAgB4W0z` |
| Thu, 29 Oct 2026 08:18:22 GMT | `acct_1UU8NqJnhDRQ22zQ` | `/v1/balance` | 200 | `req_d44P2Dp9K6NW39` |
| Thu, 29 Oct 2026 08:18:23 GMT | `acct_1UU8NqJnhDRQ22zQ` | `/v1/payment_intents · limit=100` | 200 | `req_g8g9TJf0fn45Cb` |
| Thu, 29 Oct 2026 08:18:24 GMT | `acct_1UU8NqJnhDRQ22zQ` | `/v1/charges · limit=100` | 200 | `req_qbBwRGU3LCP48G` |
| Thu, 29 Oct 2026 08:18:25 GMT | `acct_1UTkpsJnhDryoncr` | `/v1/balance` | 200 | `req_gGqGcjE8E8XlFd` |
| Thu, 29 Oct 2026 08:18:26 GMT | `acct_1UTkpsJnhDryoncr` | `/v1/payment_intents · limit=100` | 200 | `req_8f1UBTfTRO4di2` |
| Thu, 29 Oct 2026 08:18:27 GMT | `acct_1UTkpsJnhDryoncr` | `/v1/charges · limit=100` | 200 | `req_8ZMgiiTM5Jycr9` |
| Thu, 29 Oct 2026 08:18:28 GMT | `acct_1UTkHeJnhDXaxfBd` | `/v1/balance` | 200 | `req_r6MlCo9EI2YLk5` |
| Thu, 29 Oct 2026 08:18:30 GMT | `acct_1UTkHeJnhDXaxfBd` | `/v1/payment_intents · limit=100` | 200 | `req_5qN96ZbuIHB66I` |
| Thu, 29 Oct 2026 08:18:32 GMT | `acct_1UTkHeJnhDXaxfBd` | `/v1/charges · limit=100` | 200 | `req_Sm1IBLN0llpB0h` |
| Thu, 29 Oct 2026 08:18:32 GMT | `acct_1UTKTHJnhD0xjF0b` | `/v1/balance` | 200 | `req_YKrOlK3i71fN5g` |
| Thu, 29 Oct 2026 08:18:33 GMT | `acct_1UTKTHJnhD0xjF0b` | `/v1/payment_intents · limit=100` | 200 | `req_5YE4EL6HrBFx4L` |
| Thu, 29 Oct 2026 08:18:34 GMT | `acct_1UTKTHJnhD0xjF0b` | `/v1/charges · limit=100` | 200 | `req_tILE7aoU0IsekH` |
| Thu, 29 Oct 2026 08:18:36 GMT | `acct_1UTK7rJnhDvqBsAp` | `/v1/balance` | 200 | `req_ZzmyEcmzj8K6Ln` |
| Thu, 29 Oct 2026 08:18:37 GMT | `acct_1UTK7rJnhDvqBsAp` | `/v1/payment_intents · limit=100` | 200 | `req_NArsdprNsHBKyz` |
| Thu, 29 Oct 2026 08:18:38 GMT | `acct_1UTK7rJnhDvqBsAp` | `/v1/charges · limit=100` | 200 | `req_O29vSoKJHvU65C` |
| Thu, 29 Oct 2026 08:18:40 GMT | `acct_1UTIOlJnhD6sosYk` | `/v1/balance` | 200 | `req_MKA32OTfxRABDg` |
| Thu, 29 Oct 2026 08:18:41 GMT | `acct_1UTIOlJnhD6sosYk` | `/v1/payment_intents · limit=100` | 200 | `req_1NTpUi9CQl9WGj` |
| Thu, 29 Oct 2026 08:18:42 GMT | `acct_1UTIOlJnhD6sosYk` | `/v1/charges · limit=100` | 200 | `req_2M9FSXzLiiifsq` |
| Thu, 29 Oct 2026 08:19:29 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/payment_intents/pi_3USdJ6JnhDgmiMAM1JWxToC5` | 200 | `req_McCr82n2BBa7zs` |
| Thu, 29 Oct 2026 08:19:34 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/checkout/sessions · limit=100; payment_intent=pi_3USdJ6JnhDgmiMAM1JWxToC5` | 200 | `req_K6D6JVGHYf4gzk` |
| Thu, 29 Oct 2026 08:19:35 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/charges · limit=100; payment_intent=pi_3USdJ6JnhDgmiMAM1JWxToC5` | 200 | `req_FLD3dcSl1KqgDP` |
| Thu, 29 Oct 2026 08:19:35 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/charges/ch_3USdJ6JnhDgmiMAM1hgP48Kw` | 200 | `req_XSUKBTuRz4Xt3r` |
| Thu, 29 Oct 2026 08:19:35 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/balance_transactions/txn_3USdJ6JnhDgmiMAM1BlZMYI9` | 200 | `req_isZL50CYr1dxZR` |
| Thu, 29 Oct 2026 08:19:36 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/transfers · limit=100` | 200 | `req_bUrX06l2MCPGcP` |
| Thu, 29 Oct 2026 08:19:36 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/transfers/tr_3USdJ6JnhDgmiMAM1Fh8JP66` | 200 | `req_xn86JVaq1ZO9hp` |
| Thu, 29 Oct 2026 08:19:37 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/balance_transactions/txn_3USdJ6JnhDgmiMAM1lK4DhQS` | 200 | `req_vbmosTzNLkezPQ` |
| Nicht geliefert | `acct_1USFInJnhDfTGhs9` | `/v1/charges/py_1USdJCJnhDfTGhs9nq4LKHMc` | 403 | Nicht geliefert |
| Thu, 29 Oct 2026 08:19:37 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/transfers/tr_3USdJ6JnhDgmiMAM1Fh8JP66/reversals · limit=100` | 200 | `req_5azspA3w3UFTYO` |
| Thu, 29 Oct 2026 08:19:38 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/application_fees · limit=100` | 200 | `req_rQId2H9vdd5VLW` |
| Thu, 29 Oct 2026 08:19:38 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/balance_transactions · limit=100; source=ch_3USdJ6JnhDgmiMAM1hgP48Kw` | 200 | `req_0avWiKQEl2mAk3` |
| Thu, 29 Oct 2026 08:20:43 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/transfers · limit=100; transfer_group=gratilo_bdb0617a-af1f-4724-b9f1-c8e0dfaf40e2` | 200 | `req_T7tkiVt7j1qKmd` |
| Thu, 29 Oct 2026 08:20:43 GMT | `acct_1UQNErJnhDgmiMAM` | `/v1/checkout/sessions/cs_test_a1lKCTUg8K0O5LhuJDBMolkw9Iz5lNnHdPICTu82UNWGkTb7yd8rDLW0A0` | 200 | `req_xke8AXBMTWn0Ml` |
| Nicht geliefert | `acct_1UQNErJnhDgmiMAM` | `/v1/accounts/acct_1USFInJnhDfTGhs9` | 403 | Nicht geliefert |
| Thu, 29 Oct 2026 08:20:45 GMT | `acct_1UQNErJnhDgmiMAM` | `/v2/core/accounts/acct_1USFInJnhDfTGhs9 · API-Version=2026-09-30.endive` | 200 | `req_v2DRNK97gVULNZI0K` |
| Nicht geliefert | `acct_1USFInJnhDfTGhs9` | `/v1/balance` | 403 | Nicht geliefert |
| Nicht geliefert | `acct_1USFInJnhDfTGhs9` | `/v1/payment_intents · limit=100` | 403 | Nicht geliefert |
| Nicht geliefert | `acct_1USFInJnhDfTGhs9` | `/v1/charges · limit=100` | 403 | Nicht geliefert |
| Nicht geliefert | `acct_1USZOlJnhDeaeFtd` | `/v1/payment_intents · limit=100` | 403 | Nicht geliefert |
| Nicht geliefert | `acct_1USZOlJnhDeaeFtd` | `/v1/charges · limit=100` | 403 | Nicht geliefert |
| Thu, 29 Oct 2026 08:22:18 GMT | `acct_1UQNErJnhDgmiMAM` | `/v2/core/accounts/acct_1USZOlJnhDeaeFtd · API-Version=2026-09-30.endive` | 200 | `req_v24wdKaMw8vpUwMZR` |

## F. Direkt an Stripe weiterleitbare Zusammenfassung

Wir haben die historische Gratilo-Sandboxzahlung jetzt im richtigen
Testplattformkonto eindeutig über Betrag und interne Transaktionsmetadaten
identifiziert. Die Gebühr von 1,06 EUR ist durch die Charge-Balance-Transaction
bestätigt. Bitte helfen Sie bei der noch fehlenden Empfängerbuchung des
inzwischen geschlossenen Connect-Kontos und bestätigen Sie die Vollständigkeit
der historischen Transferkette. Aktuell ist genau ein Transfer über 12,50 EUR
lesend nachweisbar.

| Feld | Wert |
|---|---|
| Zahlungsdatum (UTC) | 2026-10-20 13:42:24 UTC |
| Betrag | **25,63 EUR** |
| Stripe-Test-/Plattformkonto | `acct_1UQNErJnhDgmiMAM` |
| PaymentIntent | `pi_3USdJ6JnhDgmiMAM1JWxToC5` |
| Charge | `ch_3USdJ6JnhDgmiMAM1hgP48Kw` |
| Balance Transaction der Zahlung | `txn_3USdJ6JnhDgmiMAM1BlZMYI9` |
| Transfer(s) | `tr_3USdJ6JnhDgmiMAM1Fh8JP66` — **12,50 EUR**, erstellt 2026-10-20 13:42:31 UTC |
| Transfer-Balance-Transaction (Plattform) | `txn_3USdJ6JnhDgmiMAM1lK4DhQS` — `amount=-1250`, `fee=0`, `net=-1250` Cent |
| Empfängerkonto/-konten | `acct_1USFInJnhDfTGhs9` — per V2 `closed=true`, Testmodus |
| Empfängerzahlung | `py_1USdJCJnhDfTGhs9nq4LKHMc`; Objekt nicht lesbar, Empfänger-Balance-Transaction **nicht ermittelt** |
| Stripe-Gebühr | **106 Cent / 1,06 EUR** |
| Gebührenaufschlüsselung | Eine Position: `type=stripe_fee`, `description=Stripe processing fees`, `amount=106`, `currency=eur`, `application=null` |
| Nettobetrag der Zahlungsbuchung | **2457 Cent / 24,57 EUR** |
| Behauptete Gebühr von 1,06 EUR bestätigt? | **Ja** |
| Connect-Modell | Separate Charges and Transfers; `source_transaction` am Transfer verweist exakt auf die Charge |
| Transfergruppe | `gratilo_bdb0617a-af1f-4724-b9f1-c8e0dfaf40e2` |
| Application Fee | Charge-Felder `null`; keine zugeordnete Application Fee in vollständiger Plattformliste |
| Offene Punkte | Historische Empfängerbuchung/Balance Transaction des geschlossenen Kontos; Bestätigung, ob weitere Transfers existieren; gegebenenfalls interne noch offene Allokation |

Interne Gratilo-Transaktion: `bdb0617a-af1f-4724-b9f1-c8e0dfaf40e2`.
Die Gebühren sind separat von Transferbeträgen zu betrachten: Der Checkout
enthält einen belegten Aufschlag von 63 Cent; die tatsächliche Stripe-Gebühr ist
43 Cent höher. Mit dem aktuell einzigen nachgewiesenen Transfer bleibt aus
Zahlungs- und Transferbuchung ein Plattformsaldo von **+12,07 EUR**; dies ist
keine behauptete Gewinnmarge oder vollständige Verteilung über 25,00 EUR.

## G. Optionaler abgestimmter Sandbox-Test für die offene Empfängerseite

Nach einem gesonderten Folgeauftrag wurden zwei neue SCT-Sandbox-Vergleichstests
mit offenen Empfängerkonten ausgeführt. Ergebnisse und vollständige neue IDs:
[Einzel-/Pool-Testprotokoll](stripe-sandbox-single-pool-test.md).
Diese späteren Testzahlungen ändern weder die historischen Objekte noch den
fehlenden Zugriff auf die historische geschlossene Empfängerseite. Der folgende
Vorschlag beschreibt den ursprünglichen Stand vor diesem Folgeauftrag.

**Stand beim Abschluss der historischen Untersuchung: noch nicht ausgeführt.** Die historische Zahlung und die 1,06 EUR sind bestätigt;
offen bleibt die Empfängerbuchung. Ein neuer Test ersetzt deren historischen
Nachweis nicht. Falls Stripe die geschlossene Testkontobuchung nicht bereitstellen
kann, wäre ein separat autorisierter Vergleichstest mit weiterhin zugänglichen
Testkonten sinnvoll.

Vorher gemeinsam festlegen: exaktes Testplattformkonto, zugängliche Empfängerkonten,
SCT für Einzel- und Teamtrinkgelder, gewünschter fester Empfänger-Nettobetrag,
Gesamtbelastung, Verteilung und Tragung von Plattformkosten/Fehlbeträgen durch
Gratilo. Finale Kontokonfiguration, konkrete Connect-Konditionen und Live-Zulassung
für Arbeitnehmer ohne Gewerbe bleiben **offene fachliche Rahmenbedingungen**, keine
hier verifizierten Vertragszusagen.

Nach gesonderter Freigabe wären je ein Einzel- und Teamfall mit eindeutiger
Testlauf-ID zu protokollieren: Checkout-/PI-/Charge-IDs, Kontokontexte, UTC-Zeiten,
Status und `livemode=false`; vollständige Zahlungs-Balance-Transaction samt
`fee_details`; sämtliche Transfers mit Source-Charge, Gruppe, Zielkonto,
Plattform- und Empfänger-Balance-Transactions sowie vorhandenen Reversal-Feldern.
Alle Centgleichungen, Empfänger-Nettozusagen und Teamanteilsummen wären gegen
die tatsächlichen Buchungen zu prüfen. Später/periodisch berechnete Connect-
Kosten benötigten separate Belege. Keine zusätzlichen Bank-Payouts nur zur
Diagnose auslösen. Keine Secrets, Karten- oder Kundendaten protokollieren.

## H. Repository-Hinweise und Sicherheitsprüfung

Der Ausgangshinweis stammt aus
[stripe-fees-and-pool-members.adoc](stripe-fees-and-pool-members.adoc), Abschnitt
„Verifizierter Fehlerfall“ vom 20.10.2026. Die Kontotrennung war bereits in
[stripe-commission-diagnosis.adoc](stripe-commission-diagnosis.adoc) dokumentiert.
Die Übereinstimmung der heutigen Stripe-Metadaten mit der historischen internen
Transaktions-ID bestätigt die Zuordnung; Repository-Daten allein wurden nicht
als API-Beweis behandelt.

Vorhandene Stripe-Testskripte und Backend-Fake-Tests dienten nur als Hinweise.
Insbesondere wurden keine Testzahlung, keine Webhook-POSTs, keine Listener-
Registrierung, keine Transfers, Refunds, Payouts oder Kontokonfigurationsänderungen
ausgeführt. `docker-compose/test_stripe_local.py` und mutierende Sandbox-
Start-/Initialisierungskommandos wurden nicht ausgeführt. AWS wurde nur zum
lesenden Abruf des benannten Test-Secrets verwendet; keine Infrastrukturänderung.

Die gespeicherten Prüfwerte bestätigen beidseitige PI-/Charge- und
Charge-/Balance-Verknüpfung, exakte 2563 Cent EUR, erfolgreichen Teststatus,
106 Cent vollständige Gebührenpositionen, Source-Charge-Verknüpfung des Transfers,
beidseitige Transfer-/Plattformbuchungsverknüpfung und beide Centgleichungen.
