"""Probe the running isolated sandbox without creating Stripe payments or accounts."""
import hashlib
import hmac
import json
import re
import sys
import time
import urllib.error
import urllib.request

import stripe_test as local


def request(path, payload=None, signature=None):
    headers = {}
    if payload is not None:
        headers["Content-Type"] = "application/json"
    if signature is not None:
        headers["Stripe-Signature"] = signature
    query = urllib.request.Request("http://localhost:3001" + path, data=payload, headers=headers)
    try:
        with urllib.request.urlopen(query, timeout=15) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, b""


def signed(payload, secret, timestamp=None):
    timestamp = str(int(time.time()) if timestamp is None else timestamp)
    digest = hmac.new(secret.encode(), timestamp.encode() + b"." + payload, hashlib.sha256).hexdigest()
    return "t=" + timestamp + ",v1=" + digest


def main():
    values = local.read_env(local.ENV_FILE)
    local.validate(values)
    if request("/")[0] != 200:
        raise ValueError("Local frontend is not reachable")
    status, body = request("/user-service/features")
    if status != 200 or json.loads(body) != {"tipPoolsEnabled": values["FEATURE_TIP_POOLS_ENABLED"] == "true"}:
        raise ValueError("Local backend feature flag differs from configuration")
    if request("/payment-service/transactions")[0] not in (401, 403):
        raise ValueError("Anonymous access to private transactions was not rejected")
    endpoint = "/payment-service/webhooks/stripe"
    # No transaction/account metadata: this event cannot create financial records.
    payload = json.dumps({"id": "evt_local_signature_probe", "type": "local.signature_probe",
                          "livemode": False, "data": {"object": {}}}).encode()
    secret = values["STRIPE_WEBHOOK_SECRET"]
    if request(endpoint, payload)[0] != 401:
        raise ValueError("Unsigned webhook was not rejected")
    if request(endpoint, payload, signed(payload, secret, int(time.time()) - 600))[0] != 401:
        raise ValueError("Expired webhook was not rejected")
    if request(endpoint, payload, signed(payload, "incorrect-local-secret"))[0] != 401:
        raise ValueError("Invalid signature was not rejected")
    for _ in range(2):
        if request(endpoint, payload, signed(payload, secret))[0] != 200:
            raise ValueError("Valid signed sandbox webhook was not acknowledged")
    live_payload = payload.replace(b'"livemode": false', b'"livemode": true')
    if request(endpoint, live_payload, signed(live_payload, secret))[0] != 401:
        raise ValueError("Live-mode event was not rejected")
    query = "SELECT count(*) FROM user_mgmt.databasechangelog UNION ALL SELECT count(*) FROM payment_mgmt.databasechangelog;"
    database = local.compose(values, "exec", "-T", "postgres", "psql", "-U", "postgres", "-d", "gratilodb", "-Atc", query, capture=True)
    applied = database.stdout.strip().splitlines()
    if len(applied) != 2 or not all(int(count) > 0 for count in applied):
        raise ValueError("Both local Liquibase changelogs must contain applied migrations")
    logs = local.compose(values, "--profile", "webhooks", "logs", "--no-color", "stripe-cli", capture=True)
    secrets = re.findall(r"whsec_[A-Za-z0-9]+", logs.stdout + logs.stderr)
    if not any(hmac.compare_digest(value, secret) for value in secrets):
        raise ValueError("Container listener signing secret differs or listener is not ready; run webhook-secret and recreate the stack")
    print("PASS: local frontend/APIs, authentication, feature flags, both migrations, signed/replayed sandbox webhook, rejection of unsigned/expired/invalid/live events, matching listener secret.")
    print("No Stripe payment, Connect account or financial record was created by this probe.")


if __name__ == "__main__":
    try:
        main()
    except ValueError as error:
        print("Local sandbox probe failed: " + str(error), file=sys.stderr)
        sys.exit(1)
    except (OSError, local.subprocess.SubprocessError, urllib.error.URLError):
        print("Local sandbox probe failed; raw responses and credentials suppressed. Check container health, migrations and listener configuration.", file=sys.stderr)
        sys.exit(1)
