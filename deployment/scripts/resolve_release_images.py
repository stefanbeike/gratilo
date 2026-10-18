"""Resolve a published stable release to immutable, consistent ECR digests (read-only)."""
import json
import os
import re
import subprocess
import sys

from semver_release import DIGEST, SERVICES, stable_version


def resolve(version, account, region, describe, expected_commit=None):
    if stable_version(version) is None or len(version) > 128:
        raise ValueError("Expected vMAJOR.MINOR.PATCH without prerelease or leading zeroes")
    if account != "070351676632" or region != "eu-central-1":
        raise ValueError("Unexpected deployment account or region")
    images, common_tags = {}, None
    for service in SERVICES:
        response = describe(service, version)
        details = response.get("imageDetails", [])
        if len(details) != 1:
            raise ValueError("Release image missing or ambiguous: " + service)
        image = details[0]
        digest = image.get("imageDigest", "")
        tags = image.get("imageTags", [])
        if not DIGEST.fullmatch(digest) or version not in tags:
            raise ValueError("Invalid image metadata: " + service)
        provenance = {tag for tag in tags if re.fullmatch(r"sha-[0-9a-f]{40}-[0-9]+-[0-9]+", tag)}
        common_tags = provenance if common_tags is None else common_tags & provenance
        images[service] = f"{account}.dkr.ecr.{region}.amazonaws.com/gratilo/{service}@{digest}"
    if not common_tags:
        raise ValueError("Images must share the same commit/run/attempt tag")
    if expected_commit is not None:
        common_tags = {tag for tag in common_tags if tag.startswith("sha-" + expected_commit + "-")}
        if not common_tags:
            raise ValueError("ECR build commit differs from the selected Git release tag")
    return images, sorted(common_tags)[0]


def verify_publish_outputs(images, provenance, env):
    expected_build = env.get("EXPECTED_BUILD_TAG")
    if expected_build is not None:
        if expected_build != provenance:
            raise ValueError("Published build provenance differs from this workflow's artifacts")
        for name, image in images.items():
            key = "EXPECTED_" + name.upper().replace("-", "_") + "_IMAGE"
            if env.get(key) != image:
                raise ValueError("ECR release differs from confirmed publish output: " + name)


def main():
    # Validate input before constructing any CLI request.
    version = os.environ.get("RELEASE_VERSION", "")
    account, region = os.environ.get("AWS_ACCOUNT_ID"), os.environ.get("AWS_REGION")
    if stable_version(version) is None or len(version) > 128:
        raise ValueError("Expected stable version vMAJOR.MINOR.PATCH")
    commit_result = subprocess.run(["git", "rev-parse", "--verify", "refs/tags/" + version + "^{commit}"],
                                   capture_output=True, text=True)
    commit = commit_result.stdout.strip()
    if commit_result.returncode or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Selected Git release tag is missing")
    if subprocess.run(["git", "merge-base", "--is-ancestor", commit, "origin/main"], capture_output=True).returncode:
        raise ValueError("Selected release commit must belong to main history")

    def describe(service, tag):
        result = subprocess.run(["aws", "ecr", "describe-repositories", "--repository-names", "gratilo/" + service,
                                 "--region", region, "--output", "json"], capture_output=True, text=True)
        if result.returncode or json.loads(result.stdout)["repositories"][0]["imageTagMutability"] != "IMMUTABLE":
            raise ValueError("Repository must be readable and IMMUTABLE: " + service)
        result = subprocess.run(["aws", "ecr", "describe-images", "--repository-name", "gratilo/" + service,
                                 "--image-ids", "imageTag=" + tag, "--region", region, "--output", "json"],
                                capture_output=True, text=True)
        if result.returncode:
            raise ValueError("Published release image not available: " + service)
        return json.loads(result.stdout)

    images, provenance = resolve(version, account, region, describe, expected_commit=commit)
    verify_publish_outputs(images, provenance, os.environ)
    with open(os.environ["GITHUB_ENV"], "a") as output:
        output.write("IMAGE_TAG=" + version + "\n")
        for name, image in images.items():
            output.write(name.upper().replace("-", "_") + "_IMAGE=" + image + "\n")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            summary.write(f"## Selected release\n\nVersion: `{version}`\n\nBuild: `{provenance}`\n\n")
            for name, image in images.items():
                summary.write(f"- {name}: `{image}`\n")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError):
        sys.exit("Release selection failed: check version, all three immutable images and common build provenance; provider details suppressed.")
