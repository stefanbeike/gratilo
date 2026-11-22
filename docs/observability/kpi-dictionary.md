# KPI-Dictionary

Grafana aktualisiert SQL-Aggregate standardmäßig jede Minute. Prometheus scrapt alle 15 Sekunden. Grafana und der Prometheus-Alarmspiegel bewerten alle 30 Sekunden. Business-Backlogs sind Gauges. Neue technische Ereignisse sind Counter, Laufzeiten Histogramme.

Geldbeträge bleiben Integer-Minor-Units. SQL verwendet für Summen, Mittelwerte, Median und Quotienten exaktes `numeric`. Erst die Anzeige teilt EUR-Cent durch 100. Alle monetären SQL-Panels und ihre Zahlungsanzahlen sind auf EUR begrenzt. Der Modus stammt aus dem neuen Audit oder ersatzweise einem historischen `cs_test_`-/`cs_live_`-Präfix. Nicht bestimmbare Zeilen fehlen in beiden Modusauswahlen. Zahlungsmodell folgt dem Audit-Typ oder den gespeicherten Pool-Merkmalen und Allokationen.

Die Filtergrenzen sind UTC und halboffen `[von, bis)`. Event Time verwendet bei Refund, Dispute und Transfer Stripe-Objektzeit. Lokale Buchungs-, Erfassungs-, Änderungs- und Prüfzeit sind Processing Time. Statusverteilungen zeigen den letzten gespeicherten Zustand. Die UTC-Woche beginnt montags. Bestand und Backlogs beziehen sich auf jetzt.

## Zahlungen und Gebühren

| KPI-Schlüssel | Definition und Quelle | Status, Zeit, Einheit, Vollständigkeit |
| --- | --- | --- |
| `booked_count, tip_volume, tip_mean, tip_median, tip_by_kind` | Einmal je Stripe-TIP. Unverändertes `transactions.amount_minor`, Mittel und exakter Median. | `booked_at`, BOOKED/COMPLETED/REFUNDED/DISPUTED/CHARGEBACK. Anzahl bzw. EUR. Zeigt Brutto-Trinkgeld vor Refunds. |
| `checkout_attempts, checkout_sessions, intent_observed, charge_observed, webhook_linked` | Persistierte Versuche, Session-Referenz, PI-/Charge-Referenz im Audit und deduplizierter Audit-Webhook je Versuch. | Transaktionserstellung, Webhook-Aufzeichnung bis Fensterende. Stück. Frühe 4xx und QR fehlen. |
| `checkout_rate` | Gebuchte Versuche mit Stripe-Session bis Filterende geteilt durch alle Versuche mit Session in der Erstellungs-Kohorte. | Erstellungs- und Buchungszeit, Quote. Ohne Nenner unbekannt. |
| `tx_status, pending, failed, cancelled, expired, intent_status` | Aktuelle Transaktionszustände bzw. beobachteter Checkout-/PI-Zustand im neuen Audit. | Erstellungs-Kohorte, Stück. PENDING/AUTHORIZED offen. Expired erfordert Nachweis. |
| `booking_seconds` | Mittlere Dauer von Anlage zu lokaler verifizierter Buchung. | `booked_at`, Sekunden. Nur nichtnegative Dauern. |
| `gross, commission, service_net, tax, pricing_counts` | Eingefrorene Werte `charged_minor, platform_fee_minor, platform_fee_net_minor, platform_fee_tax_minor` und gespeicherte Version. | `booked_at`, EUR oder Anzahl. Alte fehlende Snapshots bleiben offen. |
| `commission_rate` | Vertragliche Brutto-Provision geteilt durch Trinkgeld derselben Menge mit Provisionssnapshot. | `booked_at`, Quote. Leerer Nenner bleibt unbekannt. |
| `stripe_processing, paired_estimate, fee_variance, fees_by_bearer` | `actual_fee_minor` und `estimated_fee_minor` auf derselben Vergleichsmenge. Kostenträger laut eingefrorenem Charge- und Gebührenmodell. | `booked_at`, EUR, COMPLETE plus passender Fee-Audit MATCHED/DIFFERENCE und ohne Fehler. Ausgewiesener Kostenscope gilt je Gebührenmodell. |
| `platform_stripe_costs` | Aggregiertes `platformBorneCostsMinor` des vollständigen neuen Stripe-Audits. | `booked_at`, EUR, nur zugeordnete belegte Kosten. |
| `direct_application_fees` | Belegte Stripe-Application-Fee-Bruttobeträge aus vollständigen SINGLE-Audits. | `booked_at`, EUR. SCT-Poolprovision verbleibt im Plattformrest. |
| `revenue, revenue_mean, revenue_by_kind, revenue_by_pricing` | Neuer verifizierter `platformResidualMinor` abzüglich eingefrorener Umsatzsteuer. Korrespondierender Fee-Audit muss einen geprüften Nettoerlös führen. Die Kosten und Transfers im Plattformrest werden nicht erneut abgezogen. | `booked_at`, EUR, aktuell BOOKED/COMPLETED, COMPLETE, gleiche Charge, keine Refunds oder Disputes. Fehlende Steuer-/Kostenevidenz liefert NULL. Zahlungsbezogene Teilmenge ohne Betriebskosten und ohne Aussage zu Bilanzgewinn. |
| `unknown_revenue, unknown_costs, audit_gaps` | Gebuchte Zahlungen ohne verifizierbaren Wert bzw. ohne vollständigen neuen Audit. | `booked_at`, Stück. Auch nicht enrollte Altdaten. |
| `audit_status, oldest_audit, audit_lag, audit_missing` | Aktueller Auditstatus, Alter seit Enrollment, Überfälligkeit ab `next_check_at` und feste Lückencodes. | Bestand, Stück oder Sekunden. Unbekannter Anfangszustand wird als fehlende Evidenz behandelt. |
| `refund_count, refund_volume, dispute_count, transfer_reversals` | Erfolgte Refunds, Disputes und Reversals aus vollständiger Root- bzw. revisionsgleicher Transfer-Evidenz. | Provider-`created`, EUR oder Stück. Untergrenze durch fehlende Audits. Finale Refund-Provision bleibt gemäß bestehender Policy offen. |

## Pools, Nutzer und Payouts

| KPI-Schlüssel | Definition und Quelle | Status, Zeit, Einheit, Vollständigkeit |
| --- | --- | --- |
| `pools_all, pools_active, pools_deleted, pools_new` | `pools`, aktueller Lifecycle und neuer Bestand. | Bestand inkl. CLOSING, ACTIVE und DELETED, bzw. `created_at`. Stück. |
| `memberships, members, members_distribution, invitations` | Aktive Pool-Mitgliedschaftszeilen, eindeutige Personen, Größenklasse pro Pool und offene Einladung. | Aktiver Pool/Member, `ended_at` leer, Benutzer nicht gelöscht. Einladung INVITED und gültig. Bestand, Stück. |
| `admins_new` | Erster gespeicherter Pool eines Besitzers. | `min(pools.created_at)`, Stück. |
| `allocations, transfer_confirmed, transfer_pending, transfer_held, transfer_retry_review` | Eingefrorene `pool_transfers` gebuchter POOL-TIPs. Bestätigt erfordert Stripe-Transfer-ID. Pending, Halte- und andere Prüfgründe sind getrennt. | Allokationszeit im Fenster oder aktueller Bestand, Stück. Kein terminaler FAILED-Zustand des aktuellen Settlement-Pfads. |
| `settlement_seconds, transfer_oldest` | Von Allokation zu belegtem Stripe-Transfer bzw. Alter ältester ausstehender Allokation. | Revisionsgleicher Transfer-Audit Provider-Zeitstempel oder aktueller Bestand. Sekunden. |
| `transfer_pending_minor, reversal_pending_minor, pool_readiness_blocks` | Offenes Allokationsbudget nach Soll-Reversal, erforderliche minus bestätigte Reversals, gespeicherte RECIPIENT_NOT_READY-Fälle. | Bestand, EUR oder Stück. Empfängeranspruch nach lokalem Snapshot. |
| `registrations, verified_users` | Kontoerstellung und aktueller Bestand verifizierter, nicht gelöschter Nutzer. | `users.created_at` bzw. Zustand, Stück pro Umgebung. |
| `first_receipt, repeat_receipts` | Erste bestätigte Single-Buchung oder Audit-belegter Pool-Empfängertransfer. Wiederholt bedeutet mindestens zwei Eingänge im Zeitraum. | Buchungszeit oder Stripe-Transferzeit. Eindeutige Personen werden nur intern aggregiert. Historische Poolabdeckung ist begrenzt. |
| `connected, onboarded, charges, transfers, payouts, stale, needs_action, under_review, payout_restricted, fully_ready, connect_status` | Gespeicherte Stripe-Accounts, Capability-Flags, Workflow-Status. Stale fehlt oder ist älter als 24 h. Fully ready erfordert COMPLETE und drei gesetzte Flags. | Test-Bestand, Stück. Keine Übergangszeiten. |
| `payout_requested, payout_status, payout_open, payout_failed, payout_paid_amount, payout_seconds, payout_oldest, payout_unknown_fees` | Verwaltete negative PAYOUT-Zeile, Betrag positiv dargestellt. `STRIPE_SANDBOX` und `STRIPE` haben eigene Auswertung. | Erstellung für Anforderung/Verteilung, Zustand für offene Menge, `updated_at` für aktuelle FAILED, `booked_at` plus paid für Volumen und Dauer. Anzahl, EUR oder Sekunden. Kein Bankeingangs- oder Istkostennachweis. |

## Technische Quellen

| Quelle | Berechnung und Qualität |
| --- | --- |
| `gratilo_http_server_requests_seconds`, `gratilo_http_server_active` | HTTP-Rate, Dauerquantile und aktive Requests nach Dienst. HTTP-Histogramm hat statische Templates, Methode und Statusklasse. Erfolgsquoten für Registrierung, Login, Verifikation und Onboarding sind Route-/Statusauswertungen. |
| `gratilo_http_security_responses_total` | Fest kategorisierte 401/403/429 nach Anwendungsausgang. |
| `gratilo_operation_duration_seconds` | Timer für Checkout, Webhook, Stripe-Sync, Pool-Settlement, Payout-Abgleich, Stripe-Audit und Gebühren-Reconciliation. Durchläufe können No-op sein. |
| `gratilo_dependency_duration_seconds` | Beobachtete Stripe- und Payment-zu-User-Aufrufe. Outcomes sind Antwortklasse, Rate-Limit oder Transportfehler. |
| `gratilo_observation_events_total` | Feste Ereignisse für Session, Signatur, Modus, Deduplikation, Reconciliation, Audit, Payout-Setup und Transfer-Retry. Technische Events gehen nicht als bestätigte Buchungen in die Finanz-KPIs ein. |
| JVM, Hikari, `gratilo_build_info`, `gratilo_process_resident_bytes` und `gratilo_cgroup_*` | Laufzeit, Datenbankpool, Build, residenter Linux-Speicher und cgroup v2. Neustart ist Änderungen der Startzeit derselben Scrape-Identität. |
| `probe_success, up, pg_up, gratilo_postgres_*, ALERTS` | Stichproben aus HTTP-Probes, Scrape-Status, Exporter, SQL-Backlogs und Alarmspiegel. Bestandsdaten werden nicht als Counter exportiert. |

Ohne belastbare historische Events bleiben Aktivierungsdauer, aktive Nutzer, MAU, WAU, Retention, personenbezogene Conversion, QR-Scans und stille Checkout-Abbrüche unbekannt. Die aggregierte neue API-Telemetrie erfasst beobachtete Antworten ohne Personenbezug. Nutzerbezogene Zeitreihen benötigen ein eigens definiertes datenschutzkonformes Eventmodell.
