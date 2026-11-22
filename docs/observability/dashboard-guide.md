# Dashboard-Leitfaden

Alle Dashboards nutzen UTC, dunkles Theme und native Grafana-Panels. Die Auswahlliste oben verlinkt die anderen Dashboards und übernimmt gesetzte Zeit und Filter. Im Zeitpicker stehen 24 Stunden, 7 Tage, 30 Tage und 90 Tage zur Verfügung. Für ein Display eignet sich die Grafana-Kioskansicht mit 1-Minuten-Refresh.

| Dashboard | Verwendung |
| --- | --- |
| 01 Geschäftsüberblick | Buchungsvolumen, Anzahl, Median, Checkout-Kohorte, Nutzer und Finanznachweise |
| 02 Zahlungen und Stripe | Persistierter Start, Session, PaymentIntent, Charge, Audit-Webhook, Buchung und API-Latenz |
| 03 Erlöse, Preise und Gebühren | Vertrag, nachgewiesener Nettoerlös, Steuer, Kostenabweichung und Nachweisabdeckung |
| 04 Pools und Teams | Pools, Mitglieder, Allokationen, Transfers, offene Pflichten und Reversals |
| 05 Registrierung, Aktivierung und Wachstum | Registrierung, API-Ergebnisse, Verifikationsbestand, Connect und Zahlungseingänge |
| 06 Auszahlungen und Stripe Connect | Capability-Flags, Auszahlungslifecycle, getrennte Test- und Simulationswerte |
| 07 Backend und API-Performance | HTTP, JVM, Hikari, Version und Operationslaufzeiten |
| 08 Infrastruktur und AWS | Lokale Container- und DB-Metriken, Verfügbarkeit und CloudWatch-Zuordnung |
| 09 Sicherheit und Audit | Fehlerkategorien, Abweisungen, Abgleichskonflikte und kontrollierte Loki-Ereignisse |
| 10 Betrieb und Alarme | Grafana Alert Center, Prometheus-Zustände, Backlogs und Datenfrische |

**Umgebung** folgt der installierten Datenquelle. Demo und lokale Daten liegen in getrennten Grafana- und Datenbankinstanzen. **Dienst** filtert instrumentierte Zeitreihen. **SINGLE/POOL** und **Stripe-Modus** filtern die zugehörigen SQL-Aggregate. Simulierte Payouts haben im Testmodus einen eigenen Scope. Nutzer- und Poolbestände gehören zur Instanz.

„Unbekannt“ oder ein leerer Ergebnisbereich kennzeichnet fehlende Abdeckung. Null bedeutet, dass die Quelle im beobachteten Scope keine passenden Zeilen oder Ereignisse liefert. Momentaufnahmen können bei einer Änderung des Zeitfensters gleich bleiben. Die Panelbeschreibung erklärt Quelle und Zeitbezug.
