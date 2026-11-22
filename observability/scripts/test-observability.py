#!/usr/bin/env python3
"""Static reproducibility, disposable PostgreSQL tests, and Grafana/Compose integration."""
import argparse
import base64
from datetime import datetime, timedelta, timezone
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
import uuid

ROOT = Path(__file__).resolve().parents[2]
OBS = ROOT / "observability"
STACK = ROOT / "docker-compose/compose.monitoring.yml"


def run(*args, **kwargs):
    return subprocess.run(args, cwd=ROOT, text=True, check=True, **kwargs)


def static():
    sys.dont_write_bytecode = True
    for name in ("generate-dashboards", "generate-alerts"):
        spec = importlib.util.spec_from_file_location(name, OBS/"scripts"/(name+".py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        for path, expected in module.outputs().items():
            assert json.loads(path.read_text()) == expected, f"Regenerate {path.name}"
    boards = sorted((OBS/"grafana/dashboards").glob("*.json"))
    assert len(boards) == 10
    panels = 0
    for path in boards:
        board = json.loads(path.read_text())
        assert path.stem == board["uid"]
        used = set()
        for panel in board["panels"]:
            p = panel["gridPos"]
            cells = {(x,y) for x in range(p["x"],p["x"]+p["w"]) for y in range(p["y"],p["y"]+p["h"])}
            assert not cells & used and max(x for x,_ in cells) < 24, panel["title"]
            used.update(cells)
            if panel.get("targets"):
                assert panel["description"] and panel["datasource"]["uid"] in {
                    "gratilo-postgres", "gratilo-loki", "gratilo-prometheus"}
            if panel["type"] != "row":
                panels += 1
    assert not re.search(r"^\s+image: .*:latest\s*$", STACK.read_text(), re.M)
    print(f"Static: 10 reproducible dashboards, {panels} panels and 15 alert definitions.")


def sql_test():
    name = "gratilo-observability-sql-" + uuid.uuid4().hex[:10]

    def sql(query, output=False):
        return run("docker", "exec", "-i", name, "psql", "-qAXt", "-U", "postgres", "-d", "gratilodb",
                   "-v", "ON_ERROR_STOP=1", input=query, capture_output=output)

    run("docker", "run", "-d", "--name", name, "--network", "none",
        "-e", "POSTGRES_DB=gratilodb", "-e", "POSTGRES_PASSWORD=isolated-synthetic-only",
        "postgres:15.18-alpine", stdout=subprocess.DEVNULL)
    try:
        for _ in range(40):
            result = subprocess.run(["docker","exec",name,"pg_isready","-h","127.0.0.1","-U","postgres","-d","gratilodb"],
                                    capture_output=True)
            if not result.returncode:
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("Disposable PostgreSQL never became ready")
        sql((ROOT/"docker-compose/postgres/init-schemas.sql").read_text())
        for schema in ("user", "payment"):
            base = ROOT/f"backend/{schema}-service/src/main/resources/db/changelog"
            for relative in re.findall(r'<include\s+file="([^"]+\.sql)"', (base/"changelog-master.xml").read_text()):
                file = base / relative.removeprefix("db/changelog/")
                sql(file.read_text())
        sql("\\set monitor_environment demo\n\\set grafana_password disposable\n\\set exporter_password disposable\n"
            + (OBS/"sql/roles.sql").read_text())
        sql((OBS/"sql/metrics.sql").read_text())
        scope = "now()-interval '90 days',now(),false,'ALL'"
        assert sql(f"SELECT value FROM gratilo_monitor.payments({scope}) WHERE metric='booked_count';", True).stdout.strip() == "0"
        assert sql(f"SELECT value IS NULL FROM gratilo_monitor.payments({scope}) WHERE metric='revenue';", True).stdout.strip() == "t"
        assert sql("SELECT sum(audit_overdue+audit_review+pool_overdue+payout_failed) FROM gratilo_monitor.operations();",
                   True).stdout.strip() == "0"
        sql((OBS/"sql/demo.sql").read_text())
        sql((OBS/"sql/demo.sql").read_text())
        sql((OBS/"sql/assertions.sql").read_text())
        event_count = "SELECT count(*) FROM payment_mgmt.stripe_payment_audit_event;"
        events_before = int(sql(event_count, True).stdout)
        assert events_before > 0
        sql("""INSERT INTO payment_mgmt.stripe_payment_audit_event(payment_id,event_key,event_type,financial_data)
               SELECT payment_id,event_key,event_type,financial_data FROM payment_mgmt.stripe_payment_audit_event
               ON CONFLICT DO NOTHING;""")
        assert int(sql(event_count, True).stdout) == events_before
        sql((OBS/"sql/assertions.sql").read_text())
        for rejected in ("SELECT email FROM user_mgmt.users", "UPDATE payment_mgmt.transactions SET status='BOOKED'"):
            result = subprocess.run(
                ["docker", "exec", "-e", "PGPASSWORD=disposable", name, "psql", "-h", "127.0.0.1",
                 "-U", "gratilo_grafana", "-d", "gratilodb", "-c", rejected], capture_output=True, text=True)
            assert result.returncode != 0 and "permission denied" in result.stderr, result.stderr
    finally:
        run("docker", "rm", "-fv", name, stdout=subprocess.DEVNULL)
    print("PostgreSQL: empty and populated KPI windows, pending vs booked, EUR, mode, Single/Pool, transfers, audits, payouts, historical pricing and read-only access.")


def config(env):
    compose = ("docker", "compose", "--env-file", str(env), "-f", str(STACK))
    run(*compose, "config", "-q")
    run("docker", "compose", "--env-file", str(env), "-f", str(STACK), "-f",
        str(ROOT/"docker-compose/compose.monitoring.host-build.yml"), "config", "-q")
    run("docker", "run", "--rm", "-v", f"{OBS/'alerts'}:/rules:ro", "-w", "/rules",
        "--entrypoint", "promtool", "prom/prometheus:v3.5.2", "test", "rules", "rules.test.yml")
    run("docker", "run", "--rm", "-v", f"{OBS/'alloy/config.alloy'}:/etc/config.alloy:ro",
        "grafana/alloy:v1.13.2", "validate", "/etc/config.alloy")
    run("docker", "run", "--rm", "--network", "none",
        "-v", f"{ROOT/'frontend/nginx.conf'}:/tmp/nginx.template:ro", "--entrypoint", "sh",
        "nginx:1.30.6-alpine3.24", "-ec", """
export USER_SERVICE_HOST=127.0.0.1 PAYMENT_SERVICE_HOST=127.0.0.1
envsubst '${USER_SERVICE_HOST} ${PAYMENT_SERVICE_HOST}' < /tmp/nginx.template > /etc/nginx/conf.d/default.conf
nginx -t
nginx -g 'error_log /dev/null;'
wget -S -O /dev/null http://127.0.0.1/internal/observability/metrics 2>&1 | grep -q '404 Not Found'
""", stdout=subprocess.DEVNULL)
    print("Compose and Alloy configuration valid. Nginx returns 404 for the internal scrape path.")


def smoke(env):
    settings = dict(line.split("=",1) for line in env.read_text().splitlines() if line and not line.startswith("#"))
    grafana = f"http://127.0.0.1:{settings['OBS_GRAFANA_PORT']}"
    prom = f"http://127.0.0.1:{settings['OBS_PROMETHEUS_PORT']}"
    auth = base64.b64encode(f"admin:{settings['OBS_GRAFANA_ADMIN_PASSWORD']}".encode()).decode()
    compose = ("docker","compose","--env-file",str(env),"-f",str(STACK))

    def api(path, body=None, base=grafana):
        headers = {"Content-Type":"application/json"}
        if base == grafana:
            headers["Authorization"] = "Basic " + auth
        with urlopen(Request(base+path, json.dumps(body).encode() if body is not None else None, headers), timeout=15) as response:
            return json.load(response)

    def prom_query(expr):
        return api("/api/v1/query?" + urlencode({"query": expr}), base=prom)["data"]["result"]

    last_error = "no successful scrape"
    for _ in range(30):
        try:
            if (api("/api/health").get("database") == "ok"
                    and len(prom_query('up{job="backend"} == 1')) == 2
                    and prom_query("gratilo_postgres_audit_overdue")):
                break
            targets = api("/api/v1/targets", base=prom)["data"]["activeTargets"]
            last_error = [(t["labels"].get("job"), t["lastError"]) for t in targets if t["health"] != "up"]
        except (OSError, ValueError) as error:
            last_error = str(error)
        time.sleep(3)
    else:
        raise RuntimeError(f"Backend/database scraping not ready: {last_error}")
    assert len(api("/api/search?tag=gratilo&type=dash-db")) == 10
    assert len(api("/api/v1/provisioning/alert-rules")) == 15
    for source in ("gratilo-prometheus", "gratilo-postgres", "gratilo-loki"):
        assert api("/api/datasources/uid/"+source+"/health")["status"].lower() == "ok", source
    run(*compose, "exec", "-T", "prometheus", "promtool", "check", "config", "/etc/prometheus/config.yml")
    match = r'endpoint_template="([^"]+)"'
    for service, port in (("user-service", 8081), ("payment-service", 8082)):
        if service == "user-service":
            run(*compose, "exec", "-T", service, "sh", "-ec", """
curl -s --max-time 10 -o /dev/null http://localhost:8081/user-service/users/public/00000000-0000-0000-0000-000000000001
curl -s --max-time 10 -o /dev/null -H "Content-Type: application/json" \
  -d '{"email":"test-probe@example.invalid","password":"synthetic-invalid"}' http://localhost:8081/user-service/auth/login
""")
        command = f"""
test "$(curl -s -o /dev/null -w '%{{http_code}}' http://localhost:{port}/internal/observability/metrics)" = 404
test "$(curl -s -o /dev/null -w '%{{http_code}}' -H 'Authorization: Bearer wrong-observability-token' http://localhost:{port}/internal/observability/metrics)" = 404
test "$(curl -s -o /dev/null -w '%{{http_code}}' http://localhost:{port}/prometheus)" != 200
exec curl -fsS -H "Authorization: Bearer $OBSERVABILITY_SCRAPE_TOKEN" http://localhost:{port}/internal/observability/metrics
"""
        metrics = run(*compose,"exec","-T",service,"sh","-ec",command,capture_output=True).stdout
        assert f'service="{service}"' in metrics and 'environment="demo"' in metrics
        assert "jvm_memory_used_bytes" in metrics and "hikaricp_connections" in metrics
        assert re.search(r'(?i)(user_id|pool_id|payment_id|email|stripe_object_id)="', metrics) is None
        routes = set(re.findall(match, metrics))
        assert routes and all(not re.search(r"[a-f0-9]{8}-[a-f0-9-]{27,}", route) for route in routes), routes
        if service == "user-service":
            assert "/user-service/users/public/{id}" in routes, routes
    end = datetime.now(timezone.utc)
    start = end-timedelta(days=7)
    substitution = {"${__from}": str(int(start.timestamp()*1000)), "${__to}": str(int(end.timestamp()*1000)),
                    "${environment:sqlstring}": "'demo'", "${stripe_mode:sqlstring}": "'test'", "${kind:sqlstring}": "'ALL'",
                    "${environment:regex}": "demo", "${service:regex}": ".*", "$__range": "7d", "$__rate_interval": "1m"}
    panel_queries = 0
    for path in sorted((OBS/"grafana/dashboards").glob("*.json")):
        provisioned = api("/api/dashboards/uid/"+path.stem)["dashboard"]
        assert provisioned["uid"] == path.stem
        for panel in provisioned["panels"]:
            for query in panel.get("targets", []):
                shape = "rawSql" if "rawSql" in query else "expr"
                query = dict(query)
                for key, value in substitution.items():
                    query[shape] = query[shape].replace(key, value)
                if query["datasource"]["uid"] == "gratilo-prometheus":
                    prom_query(query["expr"])
                else:
                    payload = {"from":substitution["${__from}"], "to":substitution["${__to}"], "queries":[query]}
                    try:
                        answer = api("/api/ds/query", payload)["results"]["A"]
                    except HTTPError as error:
                        raise RuntimeError(f'{path.stem} / {panel["title"]}: {error.read().decode()}') from error
                    assert not answer.get("error"), (path.stem, panel["title"], answer.get("error"))
                    if query["datasource"]["uid"] == "gratilo-loki":
                        for _ in range(12):
                            if any(column for frame in answer.get("frames", [])
                                   for column in frame.get("data", {}).get("values", [])):
                                break
                            time.sleep(3)
                            payload["to"] = str(int(time.time() * 1000))
                            answer = api("/api/ds/query", payload)["results"]["A"]
                        else:
                            raise AssertionError("No allowed event reached Loki")
                panel_queries += 1
    for _ in range(12):
        groups = api("/api/prometheus/grafana/api/v1/rules")["data"]["groups"]
        rules = [rule for group in groups for rule in group["rules"]]
        if len(rules) == 15 and all(rule["health"] == "ok" for rule in rules):
            break
        time.sleep(3)
    else:
        raise AssertionError("Grafana did not evaluate all rules successfully")
    print(f"Smoke: 3 healthy data sources, 10 dashboards, {panel_queries} executed panel queries, "
          "15 evaluated Grafana rules, two protected Micronaut endpoints and sanitized Loki entries.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("test", choices=("static", "sql", "config", "smoke"))
    parser.add_argument("--env-file", type=Path, default=ROOT/".env.observability-demo")
    args = parser.parse_args()
    if args.test in ("static", "sql"):
        {"static": static, "sql": sql_test}[args.test]()
    else:
        {"config": config, "smoke": smoke}[args.test](args.env_file)
