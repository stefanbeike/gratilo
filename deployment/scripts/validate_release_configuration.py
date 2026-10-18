"""Validate non-secret Actions configuration before OIDC; never print values."""
import argparse
import os
import re
import sys

from semver_release import REGISTRY


def configuration_errors(stage, env):
    if stage not in ("release", "production"):
        raise ValueError("Expected release or production environment")
    errors = []
    if stage == "release":
        flag = env.get("ECR_PUBLISH_ENABLED", "")
        if flag != "true":
            state = "missing" if not flag else "not exactly true"
            errors.append(
                f"ECR_PUBLISH_ENABLED is {state}. Set the Repository variable to true; "
                "release.yml must pass it through the required ecr_publish_enabled input. "
                "Publication remains disabled.")

    account, region = REGISTRY.split(".")[0], REGISTRY.split(".")[3]
    expected = {"AWS_ACCOUNT_ID": account, "AWS_REGION": region}
    for name in ("AWS_ACCOUNT_ID", "AWS_REGION", "AWS_DEPLOY_ROLE_ARN"):
        value = env.get(name, "")
        location = f"Settings > Environments > {stage} > Environment variables"
        if not value:
            errors.append(
                f"{name} is missing for GitHub Environment {stage}. Set it under {location} "
                "or as a shared Repository variable. Variables only in another environment "
                "are not visible to this job.")
        elif name in expected and value != expected[name]:
            errors.append(f"{name} does not match the Gratilo production registry; check {location} and Repository overrides.")
        elif name == "AWS_DEPLOY_ROLE_ARN":
            valid = (value == f"arn:aws:iam::{account}:role/gratilo-github-actions-role"
                     if stage == "production" else
                     re.fullmatch(r"arn:aws:iam::" + account + r":role/[A-Za-z0-9+=,.@_/-]+", value))
            if not valid:
                errors.append(f"{name} is invalid for the expected Gratilo account and {stage} role; check {location}.")
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("environment", choices=("release", "production"))
    stage = parser.parse_args(argv).environment
    errors = configuration_errors(stage, os.environ)
    for error in errors:
        print("::error title=Gratilo configuration::" + error, file=sys.stderr)
    if errors:
        return 1
    print(f"Configuration validated for GitHub Environment {stage}; values suppressed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
