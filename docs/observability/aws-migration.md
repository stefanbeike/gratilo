# AWS-Migration

Vorhandene Bausteine sind ECS/Fargate mit gemeinsamer App-Task, ECR für unveränderliche Versionen, ALB am Frontend, RDS PostgreSQL, CloudWatch Logs und GitHub OIDC. Die Umsetzung bleibt lokal. Diese Anleitung verwendet die vorgesehenen Ressourcen aus Deployment-Templates und setzt eine separate AWS-Freigabe voraus.

| Bereich | A · Amazon Managed Grafana | B · Selbst gehostetes Grafana auf ECS/Fargate |
| --- | --- | --- |
| Aufwand und Kosten | Workspace- und aktive Nutzerkosten plus Telemetrie-/Abfragevolumen. AWS betreibt die UI. | Fargate-CPU/RAM, privater ALB, persistente Grafana-DB, Telemetrie und Betrieb. Patches, Monitoring und Rollbacks liegen beim Team. |
| Datenquellen | CloudWatch und AMP über IAM, private VPC-Verbindung für die aggregierte RDS-Quelle. Optional private Loki-Quelle für unveränderte Logpanels. | CloudWatch und AMP über Task Role, RDS über private SG, optional zentraler Loki mit S3. |
| Grafana-Zustand und Skalierung | Verwalteter Workspace. Datenquellen und Dashboards deklarativ per API/IaC. Workspace- und Graph-Version vor Import prüfen. | Dedizierte verschlüsselte RDS-Metadatenbank für Grafana. Kein gemeinsames SQLite über EFS zwischen Replikaten. Für HA Grafana-Alerting-Peers und Routing explizit konfigurieren. |
| IAM und Zugriff | IAM Identity Center oder SAML, Workspace Viewer/Editor/Admin, separate Datenrollen. | OIDC/SSO am privaten Dienst, Viewer/Editor/Admin in Grafana, ECS-Ausführungsrolle getrennt von Task-Zugriffen. |
| Alarme | Grafana-managed mit autorisierten regionalen SNS- oder genehmigten Kontaktzielen. Infrastruktur-Alarme können separat in CloudWatch leben. | Lokal versionierte Grafana-Alert-Vorlagen übernehmen und einen aktiven Kontakt je Umgebung konfigurieren. |
| Retention | AMP-/CloudWatch-Retention im Dienst konfigurieren. RDS-Audit und Backup separat. | CloudWatch für Infrastruktur beibehalten. Loki ggf. in S3 mit Compactor und Bucket-Lifecycle. Grafana-DB-PITR und Secrets sichern. |
| Netz und Secrets | Private Workspace-Verbindung für RDS und interne Benachrichtigungen. Zugang abhängig von Workspace-Rolle und Datenquellenrechten. | Keine öffentlichen Grafana-Tasks. TLS, private Subnetze, erreichbare VPC-Endpunkte und SG-Zugriffe nur zwischen berechtigten Komponenten. Secrets Manager für DB/Auth/Scrape. |

Die verwaltete Variante passt zum bestehenden AWS-Betrieb, wenn AWS-native Quellen und SSO im Vordergrund stehen. Self-hosted ist sinnvoll, wenn die lokalen Provisioning-Artefakte und die Loki-Pipeline weitgehend unverändert laufen sollen. Kosten hängen von Region, aktiven Workspace-Nutzern, Sample-Kardinalität, Logvolumen, Abfragen, Redundanz und Aufbewahrung ab. [AMG-Preismodell](https://aws.amazon.com/grafana/pricing/) · [AMG-VPC-Anbindung](https://docs.aws.amazon.com/grafana/latest/userguide/AMG-configure-vpc.html)

## CloudWatch-Zuordnung für Dashboard 08

| Panelgruppe | Namespace, Metrik und Dimension |
| --- | --- |
| ECS laufende und gewünschte Tasks | `ECS/ContainerInsights`, `RunningTaskCount` und `DesiredTaskCount`, `ClusterName,ServiceName`. |
| ECS CPU und Memory | `AWS/ECS`, `CPUUtilization,MemoryUtilization`, `ClusterName,ServiceName`. |
| Container-Restarts | `ECS/ContainerInsights`, `RestartCount`. Container-Restart-Policy separat prüfen. Task-Ersetzungen über ECS-Ereignisse sichtbar machen. |
| ALB Anfragen und 4xx/5xx | `AWS/ApplicationELB`, `RequestCount,HTTPCode_ELB_4XX_Count,HTTPCode_ELB_5XX_Count,HTTPCode_Target_4XX_Count,HTTPCode_Target_5XX_Count` mit passenden `LoadBalancer`-/`TargetGroup`-Dimensionen. Counts als Sum. |
| Target-Latenz und Health | `TargetResponseTime` p95 sowie `HealthyHostCount,UnHealthyHostCount` nach ALB und TargetGroup. |
| RDS Auslastung und Kapazität | `AWS/RDS`, `CPUUtilization,DatabaseConnections,FreeStorageSpace,ReadLatency,WriteLatency` nach `DBInstanceIdentifier`. |
| RDS PostgreSQL Deadlocks | Bestehendes `pg_stat_database_deadlocks` des privaten postgres_exporter. Bei zusätzlichem CloudWatch-Publishing als eigene Metrik mit definiertem Namespace führen. |
| Deployment-Version, -Zeit und -Fehler | Build-Info aus Artefakt-ID mit ECS-Deploy-Ereignissen aus EventBridge/CloudWatch verbinden. Erfolgreiches ECR-Publish ist keine erfolgreiche ECS-Ausrollung. |
| SLO-Verfügbarkeit | Validierte synthetische HTTPS-Probe über den freigegebenen Frontend-/ALB-Pfad, ergänzend innere Service-Probes. Probeausfälle und Stichprobenlücken getrennt behandeln. |

[Container-Insights-Metriken](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/Container-Insights-metrics-ECS.html) · [ECS-Service-Auslastung](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/service_utilization.html) · [ALB-CloudWatch-Metriken](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-cloudwatch-metrics.html) · [RDS-Metrikempfehlungen](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/appinsights-metrics-ecs.html)

## Reihenfolge der Migration

1. In einer isolierten Staging-VPC die bestehenden ECS-Task-, ALB-, RDS-, ECR- und Log-Group-Namen belegen. Separaten Test-Stack ohne Live-Zahlung starten. Image-Digests und Codec-/Versionskompatibilität dokumentieren.
2. `OBSERVABILITY_ENABLED=true`, `OBSERVABILITY_ENVIRONMENT=staging` und `OBSERVABILITY_BUILD` als ECR-Build-ID in den Backends setzen. Scrape-Token aus Secrets Manager. Ports 8081/8082 auf private Scraper-SG beschränken. Der öffentliche ALB erreicht nur das Frontend. ECS-Service-Discovery oder Sidecar-Scraping mit stabiler Task-Identität verwenden.
3. Collector im Task oder privaten Dienst ausrollen. Micrometer-Samples zu AMP über autorisiertes SigV4-Remote-Write weiterleiten. Scraper nur `aps:RemoteWrite` für den gewählten Workspace geben. Grafana-Quelle mit Query-/Label-Leserechten und festem Workspace binden. Prometheus-Regeln als AMP-Ruler-Regelgruppe übernehmen, wenn Alarmspiegel in AMP benötigt wird.
4. Read-only-Bootstrap nach App-Migration in RDS installieren. Bewährt sind getrennte DBs oder RDS-Ziele für Test und Live. Für Security-Definer einen NOLOGIN-Schemaeigner mit den benötigten SELECT-Rechten verwenden. Grafana-Rolle und Exporter wie lokal auf getrennte Logins begrenzen. CA und `verify-full` konfigurieren. Den Exporter in ein privates Netz stellen.
5. Container Insights aktivieren. CloudWatch-Quelle mit Rollen-/Regionskonfiguration aus `observability/aws/cloudwatch.datasource.example.yml` bzw. der AMG-Verwaltung anlegen. CloudWatch-Abfragen anhand der Tabelle zum Infra-Dashboard ergänzen. Bei Managed Grafana bestehende Datasource-UIDs bei Import erhalten oder im Payload konsistent ersetzen.
6. Dedizierte JSON-Events über einen Alloy-/FireLens-Sidecar erfassen. Für die vorhandenen LogQL-Panels private Loki-Quelle mit stabiler UID anbinden. Bei ausschließlichem CloudWatch Logs die Sicherheitsabfragen nach CloudWatch Logs Insights portieren. Allgemeine stdout-Logs getrennt halten und auf sensitive Felder prüfen.
7. In Variante A Dashboards und Alerting über Workspace-API/Provisioning-APIs importieren. Variante B erzeugt ein unveränderliches Grafana-Image mit den Provisioning-Dateien. Zugang ausschließlich per SSO/privatem Entry Point ermöglichen. Vor Benachrichtigungsaktivierung Kontakt und Wartungsrouting im Test prüfen.
8. Abnahme in Staging mit isolierten Quellen durchführen. Prüfen: Scrapes beider Dienste, RDS-Rollen, Limits, Dashboard-Abdeckung, Positiv-/Negativ-Alerts, Logs, Retention, Backup-Restore und laufende Kosten. Erst anschließend einen genehmigten produktiven Pilot-Release über den bestehenden OIDC-Workflow planen. Rollback über vorherige Task-Definition und Dashboard-/Rule-Version, ohne Finanzdatenkorrekturen.
