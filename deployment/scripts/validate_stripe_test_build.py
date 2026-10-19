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
        if env.get("STRIPE_TEST_CONFIRMATION") not in ("BUILD STRIPE TEST", "ENABLE STRIPE TEST PAYMENTS"):
            raise ValueError("Stripe test mode requires its dedicated confirmation")
        key = env.get("STRIPE_TEST_PUBLISHABLE_KEY", "")
        if not key.startswith("pk_test_") or len(key) <= len("pk_test_") or key != key.strip():
            raise ValueError("A valid test-mode publishable key is required for the frontend build")


if __name__ == "__main__":
    try:
        validate(os.environ)
        print("Stripe build configuration validated; no key values logged.")
    except ValueError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
