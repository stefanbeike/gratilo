#!/usr/bin/env python3
"""Generate Grafana rules and Prometheus state mirrors from one set of thresholds."""
import json
from pathlib import Path

OBS = Path(__file__).resolve().parents[1]
HTTP = "gratilo_http_server_requests_seconds"
OPS = "gratilo_operation_duration_seconds"
EVENT = "gratilo_observation_events_total"
APPLICATION_REQUEST = 'job="backend",endpoint_template!~"/health.*|/api/status"'


def http_ratio(group, bad, minimum):
    good = f'sum by ({group}) (increase({HTTP}_count{{{APPLICATION_REQUEST}}}[5m]))'
    failed = f'sum by ({group}) (increase({HTTP}_count{{{APPLICATION_REQUEST},status_class="{bad}"}}[5m]))'
    return f'(({failed} / {good}) > bool 0.05) and on ({group}) ({good} >= {minimum})'


RULES = [
    ("ServiceUnreachable", "Dienst nicht erreichbar", "critical", "2m",
     '(probe_success{job="blackbox"} == bool 0)'),
    ("HighHttp5xx", "HTTP-5xx-Quote erhöht", "critical", "5m", http_ratio("service", "5xx", 50)),
    ("HighP95", "HTTP-p95 erhöht", "warning", "5m",
     f'(histogram_quantile(0.95,sum by (service,le)(rate({HTTP}_bucket{{{APPLICATION_REQUEST}}}[5m]))) > bool 1)'
     f' and on(service) (sum by(service)(increase({HTTP}_count{{{APPLICATION_REQUEST}}}[5m])) >= 50)'),
    ("PostgresUnavailable", "PostgreSQL nicht erreichbar", "critical", "2m",
     '(pg_up{job="postgres"} == bool 0) or absent(pg_up{job="postgres"})'),
    ("ConnectionPoolPressure", "Datenbank-Pool erschöpft", "critical", "3m",
     '(hikaricp_connections_pending > bool 0) * (hikaricp_connections_active >= bool hikaricp_connections_max)'),
    ("WebhookFailures", "Webhook-Verarbeitungsfehler", "critical", "3m",
     f'sum(increase({OPS}_count{{job="backend",operation="stripe_webhook",outcome=~"5xx|exception"}}[5m])) >= bool 5'),
    ("AuditPastDue", "Stripe-Reconciliation überfällig", "warning", "5m",
     'gratilo_postgres_audit_overdue > bool 0'),
    ("AuditEvidenceConflicts", "Kritische Audit-Konflikte", "critical", "3m",
     'gratilo_postgres_audit_review > bool 0'),
    ("AuditEvidenceConflictIncrease", "Neue Konflikte in Audit-Nachweisen", "warning", "2m",
     f'sum(increase({EVENT}{{job="backend",event="audit_review_required"}}[15m])) >= bool 3'),
    ("PoolTransfersOverdue", "Freigegebene Pool-Transfers überfällig", "warning", "10m",
     'gratilo_postgres_pool_overdue > bool 0'),
    ("StripePayoutFailed", "Stripe-Testauszahlung fehlgeschlagen", "warning", "3m",
     'gratilo_postgres_payout_failed > bool 0'),
    ("WebhookSignatureSpike", "Anstieg ungültiger Webhook-Signaturen", "warning", "2m",
     f'sum(increase({EVENT}{{job="backend",event="webhook_signature_invalid"}}[5m])) >= bool 10'),
    ("ScrapeTargetUnavailable", "Monitoring-Ziel nicht erreichbar", "critical", "3m",
     '(up{job=~"backend|postgres|blackbox|pipeline|prometheus"} == bool 0) or absent(up{job="backend",service="payment-service"}) or absent(up{job="backend",service="user-service"})'),
    ("MonitoringDataStale", "Business-Datenquelle liefert keine frischen Samples", "critical", "5m",
     '(pg_exporter_last_scrape_error{job="postgres"} == bool 1) or absent_over_time(gratilo_postgres_audit_overdue[2m])'),
    ("StructuredLogWriteError", "Strukturierte Ereignisse nicht geschrieben", "warning", "3m",
     'sum by(service)(increase(gratilo_observation_log_failures_total[5m])) > bool 0'),
]


def outputs():
    prometheus = {"groups": [{"name": "gratilo-observability-state", "rules": [
        {"alert": name, "expr": f"({query}) == 1", "for": hold, "keep_firing_for": "2m",
         "labels": {"severity": severity}, "annotations": {"summary": title}}
        for name, title, severity, hold, query in RULES]}]}
    rules = []
    for name, title, severity, hold, query in RULES:
        expression = {"type": "__expr__", "uid": "__expr__"}
        rules.append({
            "uid": name, "title": title, "condition": "B",
            "for": hold, "keepFiringFor": "2m", "noDataState": "OK", "execErrState": "Error",
            "annotations": {"summary": title, "__dashboardUid__": "gratilo-10-operations", "__panelId__": "2"},
            "labels": {"severity": severity, "team": "gratilo", "stack": "observability"},
            "isPaused": False,
            "data": [
                {"refId": "A", "datasourceUid": "gratilo-prometheus", "relativeTimeRange": {"from": 300, "to": 0},
                 "model": {"expr": query, "instant": True, "range": False, "refId": "A"}},
                {"refId": "B", "datasourceUid": "__expr__", "relativeTimeRange": {"from": 0, "to": 0},
                 "model": {"datasource": expression, "expression": "A", "refId": "B", "type": "threshold",
                           "conditions": [{"evaluator": {"params": [0.5], "type": "gt"},
                                           "operator": {"type": "and"}, "query": {"params": ["B"]},
                                           "reducer": {"type": "last", "params": []}, "type": "query"}]}}
            ],
        })
    grafana = {"apiVersion": 1, "groups": [{"orgId": 1, "name": "Gratilo Betrieb",
                                         "folder": "Gratilo Observability", "interval": "30s", "rules": rules}]}
    return {
        OBS / "alerts/rules.yml": prometheus,
        OBS / "grafana/provisioning/alerting/rules.json": grafana,
    }


if __name__ == "__main__":
    for path, data in outputs().items():
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
