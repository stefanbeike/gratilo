"""Validate public-only build inputs without echoing them."""
import os
import sys


def validate(env):
    mode = env.get("STRIPE_TEST_MODE", "false")
    if mode not in ("true", "false"):
        raise ValueError("Stripe test build mode must be explicitly true or false")
    if mode == "true":
        if env.get("GITHUB_EVENT_NAME") != "workflow_dispatch" or env.get("GITHUB_REF") != "refs/heads/main":
            raise ValueError("Enabled Stripe builds require a manual main-branch run")


if __name__ == "__main__":
    try:
        validate(os.environ)
        print("Stripe build configuration validated; no key values logged.")
    except ValueError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
