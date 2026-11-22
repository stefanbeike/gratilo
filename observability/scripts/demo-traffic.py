#!/usr/bin/env python3
"""Synthetic series plus real read/invalid-signature requests. No Stripe transport."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import time
from urllib.request import Request, urlopen

START = time.monotonic()


def demo_requests():
    requests = (
        Request("http://user-service:8081/api/status"),
        Request("http://user-service:8081/user-service/users/public/00000000-0000-0000-0000-000000000001"),
        Request("http://user-service:8081/user-service/auth/login",
                b'{"email":"no-account@example.invalid","password":"synthetic-invalid"}',
                {"Content-Type": "application/json"}),
        Request("http://payment-service:8082/api/status"),
    )
    while True:
        for request in requests:
            try:
                with urlopen(request, timeout=3):
                    pass
            except OSError:
                pass
        time.sleep(5)


def metrics():
    seconds = int(time.monotonic() - START) + 1
    base = 'service="payment-service",environment="demo"'
    lines = []

    def histogram(name, labels, profile):
        total = 0
        for boundary, rate in profile:
            total += rate * seconds
            lines.append(f'{name}_bucket{{{labels},le="{boundary}"}} {total}')
        lines.append(f'{name}_bucket{{{labels},le="+Inf"}} {total}')
        lines.append(f'{name}_count{{{labels}}} {total}')
        lines.append(f'{name}_sum{{{labels}}} {seconds * sum(float(le) * rate for le, rate in profile)}')

    http_buckets = ("0.005", "0.025", "0.1", "0.25", "0.5", "1.0", "2.0", "5.0", "15.0", "60.0")
    operation_buckets = ("0.025", "0.1", "0.25", "0.5", "1.0", "2.0", "5.0", "15.0", "30.0", "60.0")
    for status, profile in (("2xx", {"0.025": 3, "0.25": 4, "0.5": 2, "2.0": 1}),
                            ("5xx", {"0.5": 1, "2.0": 1})):
        histogram("gratilo_http_server_requests_seconds",
                  f'{base},endpoint_template="DEMO_SIMULATION",method="POST",status_class="{status}"',
                  [(le, profile.get(le, 0)) for le in http_buckets])
    for outcome in ("2xx", "rate_limit", "5xx"):
        histogram("gratilo_dependency_duration_seconds", f'{base},dependency="stripe",outcome="{outcome}"',
                  [(le, int(le in {"0.1", "0.5", "1.0"})) for le in operation_buckets])
    for operation in ("checkout_single", "checkout_pool", "stripe_webhook", "stripe_status_sync",
                      "pool_settlement", "payout_reconciliation", "payment_audit", "fee_reconciliation"):
        outcome = "5xx" if operation == "stripe_webhook" else "returned"
        profile = {"0.1": 2, "0.5": 3, "1.0": 1}
        histogram("gratilo_operation_duration_seconds", f'{base},operation="{operation}",outcome="{outcome}"',
                  [(le, profile.get(le, 0)) for le in operation_buckets])
    for event in ("stripe_session_created", "webhook_signature_invalid", "audit_webhook_duplicate", "transfer_retry",
                  "reconciliation_failure", "payout_setup_failure"):
        lines.append(f'gratilo_observation_events_total{{{base},event="{event}"}} {seconds // 8}')
    return ("\n".join(lines) + "\n").encode()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/metrics":
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
        self.end_headers()
        self.wfile.write(metrics())

    def log_message(self, *_):
        pass


threading.Thread(target=demo_requests, daemon=True).start()
ThreadingHTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
