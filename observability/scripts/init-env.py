#!/usr/bin/env python3
"""Create independent local secrets without reading the application's .env."""
import argparse
import os
from pathlib import Path
import secrets

parser = argparse.ArgumentParser(description=__doc__)
mode = parser.add_mutually_exclusive_group()
mode.add_argument("--local", action="store_true", help="Configure the standard local stack")
mode.add_argument("--stripe-test", action="store_true", help="Configure the isolated local Stripe sandbox")
args = parser.parse_args()
os.umask(0o077)
root = Path(__file__).resolve().parents[2]
kind = "stripe-test" if args.stripe_test else "local" if args.local else "demo"
is_demo = kind == "demo"
env = root / f".env.observability-{kind}"
store = root / ".local" / "observability" / kind
store.mkdir(parents=True, exist_ok=True)
if env.exists() or (store / "scrape-token").exists():
    parser.exit(1, f"Configuration already exists for {kind}.\n")
token_file = store / "scrape-token"
values = {
    "COMPOSE_PROFILES": "demo" if is_demo else "",
    "OBS_ENVIRONMENT": "demo" if is_demo else "local",
    "OBS_GRAFANA_PORT": {"demo": "3002", "local": "3003", "stripe-test": "3004"}[kind],
    "OBS_PROMETHEUS_PORT": {"demo": "9090", "local": "9091", "stripe-test": "9092"}[kind],
    "OBS_SCRAPE_TOKEN": secrets.token_hex(32),
    "OBS_SCRAPE_TOKEN_FILE": f"../{token_file.relative_to(root).as_posix()}",
    "OBS_GRAFANA_ADMIN_PASSWORD": secrets.token_hex(20),
    "OBS_GRAFANA_DB_PASSWORD": secrets.token_hex(32),
    "OBS_EXPORTER_DB_PASSWORD": secrets.token_hex(32),
}
if is_demo:
    values.update({f"OBS_DEMO_{name}": secrets.token_hex(32)
                   for name in ("DB_PASSWORD", "JWT_SECRET", "INTERNAL_KEY")})
if args.stripe_test:
    values.update({
        "OBS_TARGET_NETWORK": "gratilo-stripe-test_default",
        "OBS_EVENT_VOLUME_PREFIX": "gratilo-observability-stripe-test",
    })
with token_file.open("x") as file:
    file.write(values["OBS_SCRAPE_TOKEN"] + "\n")
# The non-root Prometheus process must be able to read the bind-mounted file.
token_file.chmod(0o644)
with env.open("x") as file:
    file.writelines(f"{key}={value}\n" for key, value in values.items())
print(f"Created {env.name} and a private scrape credential directory.")
