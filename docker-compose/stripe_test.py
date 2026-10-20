"""Manage only the isolated local Gratilo Stripe sandbox (Linux/WSL)."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import urllib.error
import urllib.request
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
ENV_FILE = ROOT / ".env.stripe-test"
COMPOSE_FILE = ROOT / "docker-compose/compose.stripe-test.yml"
PROJECT = "gratilo-stripe-test"
EVENTS = "checkout.session.completed,checkout.session.expired,checkout.session.async_payment_succeeded,checkout.session.async_payment_failed,payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled,charge.refunded,charge.dispute.created,charge.dispute.closed,payout.paid,payout.failed,payout.canceled,account.updated"
RANDOM_FIELDS = ("LOCAL_POSTGRES_PASSWORD", "JWT_SECRET", "INTERNAL_API_KEY", "PAYMENTS_TEST_ACTIVATION_CODE")


def read_env(path):
    values = {}
    if not path.exists():
        return values
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"([A-Z][A-Z0-9_]*)=(.*)", line)
        if not match:
            raise ValueError("Invalid local env syntax; use NAME=value entries")
        key, value = match.groups()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        if key in values:
            raise ValueError("Duplicate local environment variable: " + key)
        values[key] = value
    return values


def save_env(values):
    # A private staging file plus atomic rename prevents partial configuration.
    temporary = ENV_FILE.with_name(ENV_FILE.name + ".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "w") as target:
            target.write("# Local sandbox only. Never commit or share this file.\n")
            for key, value in values.items():
                if "\n" in value or "\r" in value:
                    raise ValueError("Multiline environment values are not supported")
                target.write(key + "=" + value + "\n")
        temporary.replace(ENV_FILE)
    finally:
        temporary.unlink(missing_ok=True)


def initialize():
    if ENV_FILE.exists():
        print(".env.stripe-test already exists; preserved without changes.")
        return
    existing = read_env(ROOT / ".env")
    values = read_env(ROOT / "docker-compose/.env.example")
    for key, source, prefix in (("STRIPE_SECRET_KEY", "STRIPE_SECRET_KEY", "sk_test_"),
                                ("VITE_STRIPE_PUBLISHABLE_KEY", "VITE_STRIPE_PUBLISHABLE_KEY", "pk_test_")):
        value = existing.get(source, "")
        if key == "VITE_STRIPE_PUBLISHABLE_KEY" and not value:
            value = existing.get("STRIPE_PUBLISHABLE_KEY", "")
        if value.startswith(prefix):
            values[key] = value
    # Never reuse a potentially production webhook secret or database credential.
    values["STRIPE_WEBHOOK_SECRET"] = ""
    for key in RANDOM_FIELDS:
        values[key] = secrets.token_hex(32)
    save_env(values)
    print("Created ignored .env.stripe-test with independent local credentials. Set sandbox keys, then run webhook-secret.")


def validate(values, webhook=True):
    for key, prefix in (("STRIPE_SECRET_KEY", "sk_test_"), ("VITE_STRIPE_PUBLISHABLE_KEY", "pk_test_")):
        if not values.get(key, "").startswith(prefix) or len(values[key]) <= len(prefix):
            raise ValueError(key + " must be a sandbox key; live keys are refused")
    if webhook and (not values.get("STRIPE_WEBHOOK_SECRET", "").startswith("whsec_")
                    or len(values["STRIPE_WEBHOOK_SECRET"]) <= 6):
        raise ValueError("Run webhook-secret or set the signing secret from the local listener")
    for key in RANDOM_FIELDS:
        if len(values.get(key, "")) < 32 or values[key].startswith("replace"):
            raise ValueError(key + " needs an independent local value of at least 32 characters")
    if len(values["PAYMENTS_TEST_ACTIVATION_CODE"]) > 256:
        raise ValueError("PAYMENTS_TEST_ACTIVATION_CODE must not exceed 256 characters")
    if values.get("FEATURE_TIP_POOLS_ENABLED") not in ("true", "false"):
        raise ValueError("FEATURE_TIP_POOLS_ENABLED must be true or false")
    url = urlparse(values.get("FRONTEND_BASE_URL", ""))
    if (url.username or url.password or url.query or url.fragment or url.path not in ("", "/")
            or not url.hostname or url.hostname == "gratilo.eu" or url.hostname.endswith(".gratilo.eu")):
        raise ValueError("FRONTEND_BASE_URL must be the local frontend or a dedicated HTTPS development tunnel")
    if url.scheme == "http":
        if url.hostname not in ("localhost", "127.0.0.1") or url.port != 3001:
            raise ValueError("HTTP frontend must use localhost:3001 or 127.0.0.1:3001")
    elif url.scheme != "https":
        raise ValueError("Public development tunnels must use HTTPS")
    values["FRONTEND_BASE_URL"] = values["FRONTEND_BASE_URL"].rstrip("/")


def process_env(values):
    # File values win over ambient shell variables (including Compose project overrides).
    env = {k: v for k, v in os.environ.items() if not k.startswith(("COMPOSE_", "AWS_"))}
    env.update(values)
    return env


def compose(values, *args, capture=False, host_build=False):
    command = ["docker", "compose", "--project-name", PROJECT, "--env-file", str(ENV_FILE),
               "-f", str(COMPOSE_FILE)]
    if host_build:
        command += ["-f", str(ROOT / "docker-compose/compose.stripe-test.host-build.yml")]
    command += args
    return subprocess.run(command, cwd=ROOT, env=process_env(values), text=True,
                          capture_output=capture, check=True)


def webhook_secret(values):
    # Same sandbox key/device as the container listener. Secret never enters argv/stdout.
    env = process_env(values)
    env.update(STRIPE_API_KEY=values["STRIPE_SECRET_KEY"], STRIPE_DEVICE_NAME="gratilo-local-sandbox")
    result = subprocess.run(["stripe", "listen", "--events", EVENTS, "--print-secret"], env=env,
                            capture_output=True, text=True, timeout=45)
    match = re.fullmatch(r"whsec_[A-Za-z0-9]+", result.stdout.strip())
    if result.returncode or not match:
        raise ValueError("Stripe CLI could not obtain the listener secret; verify CLI installation and sandbox access")
    values["STRIPE_WEBHOOK_SECRET"] = match.group()
    save_env(values)
    print("Local listener signing secret saved privately. Recreate payment-service after any change.")


def check_stripe(values):
    request = urllib.request.Request("https://api.stripe.com/v1/balance",
                                    headers={"Authorization": "Bearer " + values["STRIPE_SECRET_KEY"]})
    with urllib.request.urlopen(request, timeout=20) as response:
        body = json.load(response)
    if body.get("livemode") is not False:
        raise ValueError("Stripe did not confirm sandbox mode")
    print("Stripe authenticated the sandbox key; read-only balance lookup confirmed livemode=false.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("init", "check", "check-stripe", "webhook-secret", "up", "down", "status"))
    parser.add_argument("--host-build", action="store_true", help="Build backend distributions with Gradle in WSL instead of downloading Maven dependencies inside Docker")
    args = parser.parse_args(argv)
    if args.host_build and args.command != "up":
        parser.error("--host-build is only supported with up")
    if args.command == "init":
        initialize()
        return
    values = read_env(ENV_FILE)
    if not values:
        raise ValueError("Run python3 docker-compose/stripe_test.py init first")
    if args.command in ("down", "status"):
        compose(values, "--profile", "webhooks", *( ["down", "--remove-orphans"] if args.command == "down" else ["ps"]))
        return
    validate(values, webhook=args.command not in ("webhook-secret", "check-stripe"))
    if args.command == "webhook-secret":
        webhook_secret(values)
    elif args.command == "check-stripe":
        check_stripe(values)
    else:
        compose(values, "config", "--quiet", capture=True, host_build=args.host_build)
        if args.command == "up":
            if args.host_build:
                subprocess.run(["bash", "./gradlew", "--project-cache-dir", "/tmp/gratilo-local-stripe-gradle-cache",
                                ":backend:user-service:installDist", ":backend:payment-service:installDist", "--no-daemon"],
                               cwd=ROOT, env=process_env({}), check=True)
            compose(values, "--profile", "webhooks", "up", "--build", "--detach", "--wait", "--wait-timeout", "240", host_build=args.host_build)
            print("Local Gratilo: http://localhost:3001 ; MailHog: http://localhost:18025")
        else:
            print("Local sandbox configuration valid; no resources started and no credentials printed.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError, urllib.error.URLError) as error:
        # Never echo commands, provider responses or interpolated Compose configuration.
        message = str(error) if isinstance(error, ValueError) else "Local operation failed; verify Docker/Stripe CLI and network access (response suppressed)"
        print(message, file=sys.stderr)
        sys.exit(1)
