"""Validate public-only build inputs without echoing them."""
import os
import re
import sys


def validate(env):
    mode = env.get("STRIPE_TEST_MODE", "false")
    key = env.get("STRIPE_TEST_PUBLISHABLE_KEY", "")
    if mode not in ("true", "false"):
        raise ValueError("Stripe test build mode must be explicitly true or false")
    if mode == "true":
        if env.get("GITHUB_EVENT_NAME") != "workflow_dispatch" or env.get("GITHUB_REF") != "refs/heads/main":
            raise ValueError("Enabled Stripe builds require a manual main-branch run")
        if not re.fullmatch(r"pk_test_[A-Za-z0-9]+", key):
            raise ValueError("Configure repository variable STRIPE_TEST_PUBLISHABLE_KEY with a public test key")
    elif key:
        raise ValueError("Disabled builds must not receive Stripe keys")


if __name__ == "__main__":
    try:
        validate(os.environ)
        print("Stripe build configuration validated; no key values logged.")
    except ValueError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
