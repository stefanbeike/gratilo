"""Build a release context from the existing Grafana assets and a pinned public RDS CA."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "observability/grafana"
ASSETS = ROOT / "deployment/grafana"
CA_SHA256 = "56a0cae044b6cc433971d964347401692a92ea0294e392753a3ebdaee54d8b84"
SOURCE_UIDS = {"gratilo-prometheus", "gratilo-postgres", "gratilo-loki"}


def replace_one(text, old, new):
    if text.count(old) != 1:
        raise ValueError("Grafana provisioning layout changed near " + old)
    return text.replace(old, new)


def stage(destination, ca):
    bundle = ca.read_bytes()
    if hashlib.sha256(bundle).hexdigest() != CA_SHA256:
        raise ValueError("RDS CA digest changed. Verify the AWS bundle and update the pin.")
    source_uids = set()
    dashboards = []
    for path in sorted((SOURCE / "dashboards").glob("*.json")):
        board = json.loads(path.read_text())
        uid = board["uid"]
        if path.stem != uid or not re.fullmatch(r"gratilo-[0-9]{2}-[a-z0-9-]+", uid):
            raise ValueError("Invalid or inconsistent dashboard UID")
        for panel in board["panels"]:
            for target in panel.get("targets", []):
                source_uids.add(target["datasource"]["uid"])
        dashboards.append(uid)
    if not dashboards or source_uids != SOURCE_UIDS:
        raise ValueError("Unexpected dashboard data sources or empty dashboard inventory")
    original = SOURCE / "provisioning"
    sources = (original / "datasources/sources.yml").read_text()
    if set(re.findall(r"(?m)^    uid: (\S+)$", sources)) != SOURCE_UIDS or "deleteDatasources:" in sources:
        raise ValueError("Review production data source changes")
    for old, new in (
        ("url: http://prometheus:9090", "url: $OBS_PROMETHEUS_URL"),
        ("url: http://loki:3100", "url: $OBS_LOKI_URL"),
        ("sslmode: disable", "sslmode: verify-full\n      tlsConfigurationMethod: file-path\n"
         "      sslRootCertFile: /etc/grafana/rds-ca.pem"),
    ):
        sources = replace_one(sources, old, new)
    provider = replace_one((original / "dashboards/dashboards.yml").read_text(),
                           "allowUiUpdates: false", "allowUiUpdates: false\n    disableDeletion: true")
    alert_rules = json.loads((original / "alerting/rules.json").read_text())
    rules = [rule["uid"] for group in alert_rules["groups"] for rule in group["rules"]]
    if (not rules or len(set(rules)) != len(rules)
            or set(alert_rules) != {"apiVersion", "groups"}
            or any(not re.fullmatch(r"[a-zA-Z0-9_-]+", uid) for uid in rules)):
        raise ValueError("Review production Grafana alert rule changes")
    destination.mkdir(parents=True)
    for name in ("Dockerfile", "ready.sh"):
        shutil.copyfile(ASSETS / name, destination / name)
    shutil.copytree(SOURCE / "dashboards", destination / "dashboards", dirs_exist_ok=True)
    files = {
        "provisioning/datasources/sources.yml": sources,
        "provisioning/dashboards/dashboards.yml": provider,
        "provisioning/alerting/rules.json": json.dumps(alert_rules),
        "inventory.json": json.dumps({"dashboards": dashboards, "datasources": sorted(SOURCE_UIDS), "rules": rules}),
    }
    for name, contents in files.items():
        path = destination / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents + "\n")
    (destination / "rds-ca.pem").write_bytes(bundle)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("context", type=Path)
    parser.add_argument("ca", type=Path)
    args = parser.parse_args()
    stage(args.context, args.ca)
