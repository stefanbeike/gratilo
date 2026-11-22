#!/usr/bin/env python3
"""Create independent local secrets without reading the application's .env."""
import argparse
import os
from pathlib import Path
import secrets

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--local", action="store_true", help="Configure the existing local stack")
args = parser.parse_args()
os.umask(0o077)
root = Path(__file__).resolve().parents[2]
kind = "local" if args.local else "demo"
env = root / f".env.observability-{kind}"
store = root / ".local" / "observability" / kind
store.mkdir(parents=True, exist_ok=True)
if env.exists() or (store / "scrape-token").exists():
    parser.exit(1, f"Configuration already exists for {kind}.\n")
token_file = store / "scrape-token"
values = {
    "COMPOSE_PROFILES": "" if args.local else "demo",
    "OBS_ENVIRONMENT": kind,
    "OBS_GRAFANA_PORT": "3003" if args.local else "3002",
    "OBS_PROMETHEUS_PORT": "9091" if args.local else "9090",
    "OBS_SCRAPE_TOKEN": secrets.token_hex(32),
    "OBS_SCRAPE_TOKEN_FILE": f"../{token_file.relative_to(root).as_posix()}",
    "OBS_GRAFANA_ADMIN_PASSWORD": secrets.token_hex(20),
    "OBS_GRAFANA_DB_PASSWORD": secrets.token_hex(32),
    "OBS_EXPORTER_DB_PASSWORD": secrets.token_hex(32),
}
if not args.local:
    values.update({f"OBS_DEMO_{name}": secrets.token_hex(32)
                   for name in ("DB_PASSWORD", "JWT_SECRET", "INTERNAL_KEY")})
with token_file.open("x") as file:
    file.write(values["OBS_SCRAPE_TOKEN"] + "\n")
# The non-root Prometheus process must be able to read the bind-mounted file.
token_file.chmod(0o644)
with env.open("x") as file:
    file.writelines(f"{key}={value}\n" for key, value in values.items())
print(f"Created {env.name} and a private scrape credential directory.")
