"""Require an independent human production review on this manual workflow run.

Read-only GitHub API calls. Missing protection/review/API access fails closed,
before requesting AWS credentials. Never prints response bodies or tokens.
"""
import os
import re
import sys

from semver_release import GitHub, ReleaseError, check_manual_context


def validate_approval(run, reviews, env):
    check_manual_context(env)
    if (run.get("event") != "workflow_dispatch" or run.get("head_branch") != "main"
            or run.get("head_sha") != env.get("GITHUB_SHA")
            or run.get("path", "").split("@")[0] != ".github/workflows/release.yml"
            or str(run.get("id")) != env.get("GITHUB_RUN_ID")
            or str(run.get("run_attempt")) != env.get("GITHUB_RUN_ATTEMPT")):
        raise ReleaseError("Production requires this exact manual release workflow and commit")
    initiators = {str(value).lower() for value in (
        env.get("RELEASE_ACTOR"), env.get("TRIGGERING_ACTOR"),
        run.get("actor", {}).get("login"), run.get("triggering_actor", {}).get("login")) if value}
    if not initiators or not isinstance(reviews, list):
        raise ReleaseError("Could not verify independent production approval")
    production_reviews = [r for r in reviews if any(
        e.get("name", "").lower() == "production" for e in r.get("environments", []))]
    if any(r.get("state") == "rejected" for r in production_reviews):
        raise ReleaseError("Production review contains a rejection; start a fresh workflow after review")
    for review in production_reviews:
        user = review.get("user", {})
        if (review.get("state") == "approved" and user.get("type") == "User"
                and user.get("login") and user["login"].lower() not in initiators):
            return
    raise ReleaseError("Independent production approval missing; configure Required reviewers and Prevent self-review")


def main():
    env = os.environ
    check_manual_context(env)
    run_id = env.get("GITHUB_RUN_ID", "")
    if not re.fullmatch(r"[0-9]+", run_id):
        raise ReleaseError("Missing workflow run identity")
    github = GitHub(env.get("GH_TOKEN"), env.get("GITHUB_REPOSITORY"))
    run = github.request("GET", "actions/runs/" + run_id)
    reviews = github.request("GET", "actions/runs/" + run_id + "/approvals")
    validate_approval(run, reviews, env)
    print("Independent human production approval verified for this workflow run.")


if __name__ == "__main__":
    try:
        main()
    except (ReleaseError, KeyError, TypeError, AttributeError) as error:
        print(str(error) if isinstance(error, ReleaseError) else "Approval response invalid; details suppressed", file=sys.stderr)
        sys.exit(1)
