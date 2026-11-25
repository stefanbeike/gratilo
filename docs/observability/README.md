# Gratilo Observability lokal

Die Demo startet Grafana, Prometheus, Loki, Alloy, Blackbox Exporter, postgres_exporter, zwei Micronaut-Dienste und isoliertes PostgreSQL 15. Die Dienste führen die vorhandenen Liquibase-Migrationen aus. Ein einmaliger Job installiert das Reporting-Schema und lädt gekennzeichnete synthetische Fixtures. Das App-Netz der Demo ist intern. Die Demo braucht keine Stripe-, AWS- oder Produktionszugänge.

## Voraussetzungen und Start

Voraussetzungen sind Docker mit Compose ab 2.24.4 und Python ab 3.11. Alle Befehle laufen von der Repository-Wurzel.

```bash
python3 observability/scripts/init-env.py
docker compose --env-file .env.observability-demo -f docker-compose/compose.monitoring.yml up -d --build
```

Grafana läuft auf http://localhost:3002, Prometheus auf http://localhost:9090. Benutzer ist `admin`. Das erzeugte Passwort steht in `.env.observability-demo` unter `OBS_GRAFANA_ADMIN_PASSWORD`. Bei einem vorhandenen Grafana-Volume gilt dessen gespeichertes Passwort. Im Ordner **Gratilo Observability** den Geschäftsüberblick öffnen. Die Fixtures werden einmal erzeugt und liegen bei Anlage im zurückliegenden 70-Tage-Fenster. 7 oder 30 Tage zeigen die Demo am besten.

Wenn Container-Builds keine Abhängigkeiten herunterladen können, lokal mit Java 21 bauen und das Overlay verwenden.

```bash
./gradlew :backend:user-service:installDist :backend:payment-service:installDist
docker compose --env-file .env.observability-demo -f docker-compose/compose.monitoring.yml \
  -f docker-compose/compose.monitoring.host-build.yml up -d --build
```

## Eigene lokale Gratilo-Daten

Grafana des getrennten lokalen Projekts läuft auf Port 3003, Prometheus auf 9091. Die vorbereitenden Schritte verwenden einen lokal laufenden, migrierten Gratilo-Stack. Der Bootstrap installiert die dedizierten Rollen und Reporting-Funktionen innerhalb des ausgewählten PostgreSQL-Containers.

```bash
python3 observability/scripts/init-env.py --local
docker compose --env-file .env --env-file .env.observability-local \
  -f docker-compose/docker-compose.infra.yml -f docker-compose/docker-compose.services.yml \
  -f docker-compose/compose.observed-services.yml up -d --build user-service payment-service frontend
python3 observability/scripts/bootstrap-local.py
docker compose --env-file .env.observability-local -f docker-compose/compose.monitoring.yml \
  -f docker-compose/compose.monitoring.local.yml up -d
```

### Eigene Stripe-Sandbox-Daten

Die App auf Port 3001 verwendet ihre eigene PostgreSQL-Datenbank. Dafür läuft
Grafana separat auf http://localhost:3004 und Prometheus auf http://localhost:9092.
Im Grafana-Dashboard **Nutzer, Aktivierung & Wachstum** die Umgebung `local` und
den Stripe-Modus `test` wählen. **Stripe-Onboarding abgeschlossen** zählt die
aktuell als `COMPLETE` gespeicherten Konten, unabhängig vom Zeitfenster.

Bei laufendem Stripe-Test-Stack die Monitoring-Env einmal anlegen. Dann die
Reporting-Funktionen installieren und das App-Overlay aktivieren.

```bash
python3 observability/scripts/init-env.py --stripe-test
python3 observability/scripts/bootstrap-local.py --stripe-test --container gratilo-stripe-test-postgres-1
docker compose --env-file .env.stripe-test --env-file .env.observability-stripe-test \
  -f docker-compose/compose.stripe-test.yml -f docker-compose/compose.observed-services.yml \
  up -d --build user-service payment-service frontend
docker exec gratilo-stripe-test-frontend-1 nginx -s reload
docker compose -p gratilo-observability-stripe-test --env-file .env.observability-stripe-test \
  -f docker-compose/compose.monitoring.yml -f docker-compose/compose.monitoring.local.yml up -d
```

Wenn der Stripe-Test-Stack bereits den Host-Build verwendet, bei der App das
Overlay `-f docker-compose/compose.stripe-test.host-build.yml` zwischen die beiden
anderen Compose-Dateien setzen. Mit aktuellen Images genügt `--no-build`.
Bei späteren App-Starts dieselben Compose-Dateien verwenden, damit Scraping
und Log-Mounts aktiv bleiben.
Der Grafana-Benutzer ist `admin`. Das Passwort steht als
`OBS_GRAFANA_ADMIN_PASSWORD` in `.env.observability-stripe-test`.

## Tests und Betrieb

```bash
python3 observability/scripts/test-observability.py static
python3 observability/scripts/test-observability.py config
python3 observability/scripts/test-observability.py sql
python3 observability/scripts/test-observability.py smoke
./gradlew :backend:user-service:test :backend:payment-service:test
```

Bei Änderungen an Dashboards oder Schwellen die beiden Generatoren unter `observability/scripts/` ausführen und die Ergebnisse versionieren. Das Dashboard-Provisioning fragt Dateien alle 30 Sekunden ab. Datenquellen und Alerting mit einem Grafana-Neustart oder Provisioning-Reload neu einlesen.

Prometheus verwahrt 30 Tage bis zur Größenbegrenzung von 4 GB. Loki verwahrt sieben Tage. Grafana, Prometheus, Loki, Alloy-Positionen und Demo-PostgreSQL haben getrennte Volumes. Für eine konsistente Volume-Sicherung den jeweiligen Schreibdienst anhalten. Grafana-Quellen sind aus Git und gesicherten Secrets wiederherstellbar. Für vorhandene Geschäftsdaten gilt das reguläre PostgreSQL-Backupverfahren.

Zum Abschalten beim jeweiligen Compose-Aufruf `up -d` durch `down` ersetzen. `down -v` löscht dauerhaft die Volumes des gewählten Monitoring-Projekts einschließlich der Demo-Datenbank. Lokales Reporting-Schema und dedizierte DB-Rollen können nach Abschalten der Verbraucher im ausgewählten lokalen PostgreSQL separat entfernt werden.

- [Architektur und Quellen](architecture.md)
- [KPI-Dictionary](kpi-dictionary.md)
- [Dashboard-Leitfaden](dashboard-guide.md)
- [Alarme](alerts.md)
- [Sicherheitsmodell](security.md)
- [AWS-Migration](aws-migration.md)
- [Grafana im Production-Release](aws-production-release.md)
- [Fehlerdiagnose](troubleshooting.md)
