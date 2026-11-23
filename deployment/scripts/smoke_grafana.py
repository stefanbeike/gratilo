"""Exercise the packaged release image in Docker without AWS or running Gratilo stacks."""
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time


def run(*args, **kwargs):
    return subprocess.run(args, text=True, check=True, **kwargs)


def docker(*args, **kwargs):
    return run("docker", *args, **kwargs)


def ready(command, label, attempts=60):
    for _ in range(attempts):
        result = subprocess.run(command, capture_output=True, text=True)
        if not result.returncode:
            return result.stdout
        time.sleep(2)
    raise RuntimeError(label + " readiness failed: " + result.stderr.strip())


def smoke(image, folder):
    name = "gratilo-grafana-smoke-" + secrets.token_hex(5)
    postgres, prom, loki, emitter, grafana = (name + "-" + s for s in ("postgres", "prom", "loki", "emitter", "grafana"))
    cert, key = folder / "test.pem", folder / "test.key"
    run("openssl", "req", "-x509", "-nodes", "-newkey", "rsa:2048", "-days", "1",
        "-subj", "/CN=postgres", "-addext", "subjectAltName=DNS:postgres",
        "-out", str(cert), "-keyout", str(key), capture_output=True)
    (folder / "prom.yml").write_text("""
global: {scrape_interval: 1s}
scrape_configs:
  - job_name: prometheus
    static_configs:
      - targets: ["localhost:9090"]
        labels: {environment: production}
  - job_name: test_fixture
    fallback_scrape_protocol: PrometheusText0.0.4
    static_configs:
      - targets: ["emitter:8000"]
""")
    (folder / "export").mkdir()
    (folder / "export/metrics").write_text(
        'gratilo_build_info{environment="production",service="user-service",version="fixture"} 1\n'
        'gratilo_build_info{environment="production",service="payment-service",version="fixture"} 1\n')

    def start(container, alias, *args, env=None):
        variables = env or {}
        docker("run", "-d", "--name", container, "--network", name, "--network-alias", alias,
               *(value for var in variables for value in ("--env", var)), *args,
               env={**os.environ, **variables}, stdout=subprocess.DEVNULL)

    docker("network", "create", "--internal", name, stdout=subprocess.DEVNULL)
    try:
        start(postgres, "postgres", "-v", f"{folder}:/fixture:ro", "postgres:15.18-alpine",
              "sh", "-ec", "install -o postgres -g postgres -m 600 /fixture/test.key /tmp/test.key; "
              "exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/fixture/test.pem -c ssl_key_file=/tmp/test.key",
              env={"POSTGRES_PASSWORD": "disposable_fixture"})
        ready(["docker", "exec", postgres, "pg_isready", "-h", "127.0.0.1", "-U", "postgres"], "PostgreSQL")
        docker("exec", "-i", postgres, "psql", "-v", "ON_ERROR_STOP=1", "-U", "postgres", input="""
CREATE ROLE gratilo_grafana_state LOGIN PASSWORD 'disposable_fixture';
CREATE DATABASE grafana OWNER gratilo_grafana_state;
CREATE ROLE gratilo_grafana LOGIN PASSWORD 'disposable_fixture';
ALTER ROLE gratilo_grafana SET default_transaction_read_only=on;
CREATE DATABASE gratilodb;
\\connect gratilodb
CREATE SCHEMA gratilo_monitor;
CREATE FUNCTION gratilo_monitor.environment() RETURNS text LANGUAGE sql AS $$SELECT 'production'::text$$;
REVOKE ALL ON SCHEMA gratilo_monitor FROM PUBLIC;
GRANT USAGE ON SCHEMA gratilo_monitor TO gratilo_grafana;
""", stdout=subprocess.DEVNULL)
        start(emitter, "emitter", "-v", f"{folder / 'export'}:/fixture:ro", "python:3.13.13-alpine",
              "python3", "-m", "http.server", "8000", "--directory", "/fixture")
        start(prom, "prometheus", "-v", f"{folder / 'prom.yml'}:/etc/prometheus/prometheus.yml:ro",
              "prom/prometheus:v3.5.2")
        start(loki, "loki", "grafana/loki:3.6.7", "-config.file=/etc/loki/local-config.yaml")

        def grafana_up():
            start(grafana, "grafana", "-v", f"{cert}:/etc/grafana/rds-ca.pem:ro", image, env={
                "GF_SERVER_ROOT_URL": "https://grafana.example.invalid/",
                "GF_SECURITY_ADMIN_PASSWORD": "disposable_fixture",
                "GF_SECURITY_SECRET_KEY": "disposable-encryption-fixture-only",
                "GF_DATABASE_TYPE": "postgres", "GF_DATABASE_HOST": "postgres:5432", "GF_DATABASE_NAME": "grafana",
                "GF_DATABASE_USER": "gratilo_grafana_state", "GF_DATABASE_PASSWORD": "disposable_fixture",
                "GF_DATABASE_SSL_MODE": "verify-full", "GF_DATABASE_CA_CERT_PATH": "/etc/grafana/rds-ca.pem",
                "GF_PLUGINS_PREINSTALL_DISABLED": "true", "GF_ANALYTICS_REPORTING_ENABLED": "false",
                "OBS_DB_HOST": "postgres", "OBS_DB_NAME": "gratilodb", "OBS_DB_PASSWORD": "disposable_fixture",
                "OBS_PROMETHEUS_URL": "http://prometheus:9090", "OBS_LOKI_URL": "http://loki:3100",
            })
            ready(["docker", "exec", grafana, "/usr/local/bin/gratilo-grafana-ready"], "Grafana production provisioning")

        grafana_up()
        docker("exec", grafana, "curl", "-fsS", "-u", "admin:disposable_fixture", "-H",
               "Content-Type: application/json", "-d", json.dumps({"uid": "preserved-fixture", "title": "Preserved fixture"}),
               "http://127.0.0.1:3000/api/folders", stdout=subprocess.DEVNULL)
        docker("rm", "-f", grafana, stdout=subprocess.DEVNULL)
        grafana_up()
        docker("exec", grafana, "curl", "-fsS", "-u", "admin:disposable_fixture",
               "http://127.0.0.1:3000/api/folders/preserved-fixture", stdout=subprocess.DEVNULL)
        docker("stop", "--time", "2", prom, stdout=subprocess.DEVNULL)
        probe = ("docker", "exec", grafana, "/usr/local/bin/gratilo-grafana-ready")
        result = subprocess.run([*probe, "--provisioning"], capture_output=True, text=True)
        if result.returncode == 0 or "Datasource health failed: gratilo-prometheus" not in result.stderr:
            raise RuntimeError("Fresh readiness must fail when Prometheus is unavailable")
        run(*probe)
        print("Grafana: dashboards, alarm rules, three data sources, PostgreSQL TLS, persistent metadata and source-loss behavior verified.")
    finally:
        for container in (grafana, loki, prom, emitter, postgres):
            subprocess.run(["docker", "rm", "-fv", container], capture_output=True)
        subprocess.run(["docker", "network", "rm", name], capture_output=True)


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="gratilo-grafana-") as temp:
        # The fixtures are disposable. The non-root Grafana and Prometheus containers read their mounts.
        root = Path(temp)
        root.chmod(0o755)
        smoke(os.environ["GRAFANA_IMAGE"], root)
