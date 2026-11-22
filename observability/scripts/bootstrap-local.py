#!/usr/bin/env python3
"""Install the aggregate SQL into a locally running Gratilo Compose PostgreSQL."""
import argparse
import re
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--container", default="gratilodb")
args = parser.parse_args()

label = subprocess.check_output(
    ["docker", "inspect", "--type", "container", "--format",
     '{{index .Config.Labels "com.docker.compose.project"}} {{index .Config.Labels "com.docker.compose.service"}}',
     args.container], text=True).strip()
if label not in {"gratilo postgres", "gratilo-stripe-test postgres"}:
    parser.exit(1, "Use a PostgreSQL container from a local Gratilo Compose project.\n")

settings = dict(line.split("=", 1) for line in (root/".env.observability-local").read_text().splitlines()
                if line and not line.startswith("#"))
sql = ["\\set monitor_environment local"]
for setting, psql_var in (("OBS_GRAFANA_DB_PASSWORD", "grafana_password"),
                          ("OBS_EXPORTER_DB_PASSWORD", "exporter_password")):
    value = settings.get(setting, "")
    if not re.fullmatch(r"[a-f0-9]{64}", value):
        parser.exit(1, f"Expected the generated value for {setting}.\n")
    sql.append(f"\\set {psql_var} {value}")
sql.extend((root/"observability/sql"/name).read_text() for name in ("roles.sql", "metrics.sql"))
subprocess.run(["docker", "exec", "-i", args.container, "sh", "-ec",
                'export PGPASSWORD="$POSTGRES_PASSWORD"; exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
               input="\n".join(sql), text=True, check=True)
