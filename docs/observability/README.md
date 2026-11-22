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

Beim Stripe-Test-Stack die bestehende Stripe-Test-Compose-Datei und deren Env nutzen. Den tatsächlichen PostgreSQL-Containernamen mit Compose ermitteln und als `--container` angeben. `OBS_TARGET_NETWORK=gratilo-stripe-test_default` in der Monitoring-Env setzen. Die Overlay-Volumennamen sind für genau einen beobachteten lokalen App-Stack vorgesehen. Die bestehende Anwendung behält die dort konfigurierten Stripe-Einstellungen.

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
- [Fehlerdiagnose](troubleshooting.md)
