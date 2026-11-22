# Alarme

[generate-alerts.py](../../observability/scripts/generate-alerts.py) erzeugt Grafana-Regeln und Prometheus-Zustandsregeln aus gemeinsamen Definitionen. Grafana ist der Benachrichtigungskanal. Prometheus liefert die aktuellen Counts für Dashboard-Statistiken. Beide auswertenden Instanzen können während eines Zustandswechsels kurzfristig voneinander abweichen.

| Regel | Severity | Startbedingung und Dauer |
| --- | --- | --- |
| Dienst nicht erreichbar | Critical | Blackbox-Probe schlägt 2 min fehl. |
| HTTP-5xx-Quote | Critical | Über 5 % bei mindestens 50 App-Anfragen in 5 min, für 5 min. |
| HTTP-p95 | Warning | Über 1 s bei mindestens 50 App-Anfragen in 5 min, für 5 min. |
| PostgreSQL nicht erreichbar | Critical | `pg_up` null oder fehlt, für 2 min. |
| Hikari-Pool erschöpft | Critical | Wartende Threads und aktive DB-Verbindungen am Maximum, für 3 min. |
| Webhook-Fehler | Critical | Mindestens 5 HTTP-5xx/Exceptions in 5 min, für 3 min. |
| Stripe-Audit überfällig | Warning | Mindestens ein gebuchter Audit seit über 15 min fällig, für 5 min. |
| Widersprüchlicher Audit | Critical | Mindestens ein REVIEW_REQUIRED-Bestand, für 3 min. |
| Anstieg neuer Audit-Konflikte | Warning | Mindestens 3 Prüfkonflikte in 15 min, für 2 min. |
| Pool-Transfer überfällig | Warning | Bestätigte Zahlung, freigegebene Allokation seit über 24 h fällig, für 10 min. |
| Auszahlung fehlgeschlagen | Warning | Nicht simulierter Test-Payout FAILED mit letztem Update in der letzten Stunde, für 3 min. |
| Ungültige Webhook-Signaturen | Warning | Mindestens 10 in 5 min, für 2 min. |
| Monitoring-Ziel nicht erreichbar | Critical | Scrape-Ziel DOWN oder erwarteter Dienst fehlt, für 3 min. |
| Business-Datenquelle nicht frisch | Critical | Exporterfehler oder keine Backlog-Samples seit 2 min, für 5 min. |
| Strukturierte Logs konnten nicht geschrieben werden | Warning | Schreibfehler in 5 min, für 3 min. |

Regeln bleiben nach einer Auslösung noch zwei Minuten aktiv. Leere Geschäftsdaten können regulär sein. Explizite Absenzregeln prüfen Datenfrische unabhängig davon. Synthetische HTTP-Zeitreihen lösen keine HTTP-5xx-/p95-Alarme aus. Health- und Status-Probes zählen nicht zur Mindestmenge. Demo-Backlogs können Alarme erzeugen.

Der provisionierte Kontakt verwendet `example.invalid` und ist keiner aktiven Zustellroute zugewiesen. Lokales SMTP ist deaktiviert. Das Wartungsfenster-Beispiel kann bei geplantem Change der relevanten Benachrichtigungsroute zugeordnet werden. Datenaufzeichnung läuft während der Stummschaltung weiter. Schwellen nach mehreren Wochen Betrieb anhand Volumen, Schichtverteilung und tatsächlichen Fehlerraten überprüfen.

[Grafana-Dateiprovisioning für Alerting](https://grafana.com/docs/grafana/latest/alerting/set-up/provision-alerting-resources/file-provisioning/)
