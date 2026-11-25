# Fehlerdiagnose

| Symptom | Prüfung |
| --- | --- |
| Grafana nicht erreichbar | Ports 3002 für Demo, 3003 für den Standard-Stack und 3004 für die Stripe-Sandbox sowie Grafana-Logs prüfen. |
| Abgeschlossenes Sandbox-Onboarding fehlt | Grafana auf Port 3004 öffnen. Umgebung `local` und Stripe-Modus `test` prüfen. Bei unverändertem Kontostatus im Gratilo-Konto auf Port 3001 den Stripe-Status aktualisieren. |
| Login fehlgeschlagen | Die Env-Datei des gewählten Stacks prüfen. Bei persistentem Grafana-Volume gilt das gespeicherte Passwort. |
| SQL-Panels leer | Exitcode des Demo-Seed-Jobs beziehungsweise lokalen Bootstrap prüfen. PostgreSQL-Datasource-Health, Umgebung, Zeitraum und Modus abgleichen. |
| Betrag oder Gebühr unbekannt | Audit-Datenlücken und Abdeckungspanels gemeinsam prüfen. Bei Direct Charges kann der externe Istkostennachweis fehlen. |
| Backend-Scrape DOWN | Prometheus `/targets`, Dienst-Health, Netz, gesetzten Bearer-Token und lesbare Token-Datei prüfen. |
| Loki leer | Auf ein dediziertes Ereignis warten. Die Demo sendet regelmäßig absichtlich ungültige Login-Anfragen. Dateirechte von UID 1001, Alloy-Logs und Loki-Health prüfen. |
| Kurven leer | Das 7- oder 30-Tage-Fenster prüfen. SQL-Fixtures werden am ersten Start verankert. Prometheus-Raten brauchen mehrere Samples. |
| Bootstrap lehnt eine Alt-DB ab | Alte DB-Vorlagen können persistente PUBLIC-CREATE-Grants enthalten. Mit dem lokalen DB-Betreiber beheben und Bootstrap wiederholen. |
| Compose kennt !override nicht | Compose ab 2.24.4 verwenden. |
| Container-Build erreicht Gradle oder Maven Central nicht | Java-21-Distributionen lokal bauen und Host-Build-Overlay verwenden. Bei WSL-I/O-Problemen auf dem Linux-Dateisystem bauen. |

```bash
docker compose --env-file .env.observability-demo -f docker-compose/compose.monitoring.yml ps -a
docker compose --env-file .env.observability-demo -f docker-compose/compose.monitoring.yml \
  logs --tail 100 seed postgres-exporter prometheus alloy grafana
python3 observability/scripts/test-observability.py smoke
```
