"""Plan stable SemVer releases and publish scanned artifacts without overwriting tags.

Only the GitHub publish job invokes the mutating path, after environment approval.
Tests inject fake GitHub and ECR clients; this module performs no work on import.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

SERVICES = ("frontend", "user-service", "payment-service")
REGISTRY = "070351676632.dkr.ecr.eu-central-1.amazonaws.com"
STABLE = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\Z")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}\Z")


class ReleaseError(Exception):
    pass


def stable_version(tag):
    match = STABLE.fullmatch(tag)
    return tuple(map(int, match.groups())) if match else None


def next_version(tags, bump):
    if bump not in ("patch", "minor", "major"):
        raise ReleaseError("version_bump must be patch, minor or major")
    previous = max((v for tag in tags if (v := stable_version(tag)) is not None), default=(0, 0, 0))
    major, minor, patch = previous
    new = {"patch": (major, minor, patch + 1), "minor": (major, minor + 1, 0),
           "major": (major + 1, 0, 0)}[bump]
    version = "v" + ".".join(map(str, new))
    if len(version) > 128:
        raise ReleaseError("Next version exceeds the Docker tag length limit")
    return {"previous_version": "v" + ".".join(map(str, previous)), "version": version, "version_bump": bump}


class GitHubError(ReleaseError):
    def __init__(self, status):
        self.status = status
        super().__init__(f"GitHub API failed (HTTP {status}); check token permissions and repository/tag rules")


class GitHub:
    def __init__(self, token, repository):
        if not token or repository != "stefanbeike/gratilo":
            raise ReleaseError("Expected GitHub token and stefanbeike/gratilo repository context")
        self.token, self.repository = token, repository

    def request(self, method, path, payload=None):
        request = Request(f"https://api.github.com/repos/{self.repository}/{path}", method=method,
                          headers={"Authorization": "Bearer " + self.token,
                                   "Accept": "application/vnd.github+json",
                                   "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json"},
                          data=json.dumps(payload).encode() if payload is not None else None)
        try:
            with urlopen(request, timeout=30) as response:
                return json.load(response)
        except HTTPError as error:
            raise GitHubError(error.code) from None
        except (URLError, TimeoutError, ValueError):
            raise ReleaseError("GitHub API request failed; response details suppressed") from None

    def tags(self):
        names, page = [], 1
        while True:
            batch = self.request("GET", f"tags?per_page=100&page={page}")
            if not isinstance(batch, list) or any(not isinstance(item.get("name"), str) for item in batch):
                raise ReleaseError("Unexpected GitHub tag response")
            names.extend(item["name"] for item in batch)
            if len(batch) < 100:
                return names
            page += 1

    def tag_exists(self, version):
        try:
            self.request("GET", "git/ref/tags/" + version)
            return True
        except GitHubError as error:
            if error.status == 404:
                return False
            raise

    def create_tag(self, version, commit):
        # Create-only API: never PATCH, force-push, move, or delete an existing ref.
        response = self.request("POST", "git/refs", {"ref": "refs/tags/" + version, "sha": commit})
        if response.get("ref") != "refs/tags/" + version or response.get("object", {}).get("sha") != commit:
            raise ReleaseError("Git tag creation was not confirmed")


def check_version(github, plan):
    if github.tag_exists(plan["version"]):
        raise ReleaseError("Version conflict: Git tag already exists; no tags will be overwritten")
    actual = next_version(github.tags(), plan["version_bump"])
    if actual != plan:
        raise ReleaseError("Version history changed since planning; start a new complete release")


class ECR:
    def __init__(self, env):
        if env.get("AWS_ACCOUNT_ID") != "070351676632" or env.get("AWS_REGION") != "eu-central-1":
            raise ReleaseError("Expected production AWS account and eu-central-1")
        self.registry = REGISTRY
        self.folder = Path(env["RUNNER_TEMP"]) / "gratilo-images"

    def image(self, service, tag):
        return f"{self.registry}/gratilo/{service}:{tag}"

    def aws(self, *args):
        return subprocess.run(["aws", "ecr", *args, "--region", "eu-central-1"],
                              capture_output=True, text=True, check=False)

    def require_immutable(self, service):
        result = self.aws("describe-repositories", "--repository-names", "gratilo/" + service,
                          "--query", "repositories[0].imageTagMutability", "--output", "text")
        if result.returncode or result.stdout.strip() != "IMMUTABLE":
            raise ReleaseError(f"gratilo/{service} must be readable and IMMUTABLE without exclusions; configure manually")

    def digest(self, service, tag):
        result = self.aws("describe-images", "--repository-name", "gratilo/" + service,
                          "--image-ids", "imageTag=" + tag, "--query", "imageDetails[0].imageDigest", "--output", "text")
        if result.returncode:
            if "(ImageNotFoundException)" in result.stderr:
                return None
            raise ReleaseError(f"ECR image lookup failed for gratilo/{service}; details suppressed")
        digest = result.stdout.strip()
        if not DIGEST.fullmatch(digest):
            raise ReleaseError(f"Invalid ECR digest for gratilo/{service}")
        return digest

    def prepare(self, service, sha_tag, version):
        artifact = self.folder / (service + ".tar")
        if not artifact.is_file():
            raise ReleaseError("Scanned image artifact missing for " + service)
        self.docker("load", "-i", str(artifact))
        for tag in (sha_tag, version):
            self.docker("tag", f"gratilo/{service}:{sha_tag}", self.image(service, tag))

    def docker(self, *args):
        result = subprocess.run(["docker", *args], check=False)
        if result.returncode:
            raise ReleaseError("Docker operation failed: " + args[0])

    def push(self, service, tag):
        self.docker("push", self.image(service, tag))


def summary(plan, commit, outcome, rows=(), error=None):
    lines = ["## ECR SemVer release", "", f"- Release-Art: `{plan['version_bump']}`",
             f"- Vorherige Version: `{plan['previous_version']}`", f"- Neue Version: `{plan['version']}`",
             f"- Commit-SHA: `{commit}`", f"- Git-Release-Tag: `{plan['version']}`", f"- Ergebnis: **{outcome}**", ""]
    for row in rows:
        lines.extend([f"### {row['service']}", "", f"- SemVer: `{row['semver_image']}`",
                      f"- CI-Tag: `{row['sha_image']}`", f"- Digest: `{row.get('digest', 'nicht bestätigt')}`",
                      f"- Bestätigte Pushes: {', '.join(row['pushed']) or 'keine'}", ""])
    if error:
        lines.extend(["Fehler: " + str(error), "", "Keine Tags werden automatisch gelöscht, verschoben oder überschrieben.", ""])
    return "\n".join(lines) + "\n"


def publish(github, ecr, plan, commit, sha_tag, write_summary):
    rows = [{"service": service, "semver_image": ecr.image(service, plan["version"]),
             "sha_image": ecr.image(service, sha_tag), "pushed": []} for service in SERVICES]
    write_attempted, tag_confirmed = False, False
    try:
        if not re.fullmatch(r"[0-9a-f]{40}", commit) or not re.fullmatch(r"sha-" + re.escape(commit) + r"-[0-9]+-[0-9]+", sha_tag):
            raise ReleaseError("Image CI tag must identify this release commit and run")
        if not stable_version(plan["previous_version"]) or next_version([plan["previous_version"]], plan["version_bump"]) != plan:
            raise ReleaseError("Invalid planned SemVer version")
        check_version(github, plan)
        for service in SERVICES:
            ecr.require_immutable(service)
            for tag in (sha_tag, plan["version"]):
                if ecr.digest(service, tag) is not None:
                    raise ReleaseError(f"Version conflict: ECR tag exists for gratilo/{service}; no overwrite or automatic retry")
        for service in SERVICES:
            ecr.prepare(service, sha_tag, plan["version"])
        # Recheck immediately before the first publication, after all preflight work.
        check_version(github, plan)
        for row in rows:
            for tag in (sha_tag, plan["version"]):
                write_attempted = True
                ecr.push(row["service"], tag)
                row["pushed"].append(tag)
            sha_digest = ecr.digest(row["service"], sha_tag)
            version_digest = ecr.digest(row["service"], plan["version"])
            if not sha_digest or sha_digest != version_digest:
                raise ReleaseError("Image tags do not have the same confirmed digest for " + row["service"])
            row["digest"] = sha_digest
        # All six ECR references are verified before any Git reference is written.
        check_version(github, plan)
        github.create_tag(plan["version"], commit)
        tag_confirmed = True
        write_summary(summary(plan, commit, "ERFOLGREICH – drei Images, beide Tags und Git-Tag bestätigt", rows))
        return rows
    except Exception as error:
        safe = str(error) if isinstance(error, ReleaseError) else "Unexpected release failure; details suppressed"
        if write_attempted:
            outcome = "TEILVERÖFFENTLICHUNG MÖGLICH – ECR wurde beschrieben; Git-Tag " + ("bestätigt" if tag_confirmed else "nicht bestätigt")
        else:
            outcome = "ABGEBROCHEN – keine Images veröffentlicht"
        write_summary(summary(plan, commit, outcome, rows, safe))
        raise ReleaseError(outcome + ": " + safe) from None


def append_file(name, content):
    path = os.environ.get(name)
    if path:
        with open(path, "a", encoding="utf-8") as output:
            output.write(content)


def main():
    bump, commit = os.environ.get("VERSION_BUMP"), os.environ.get("GITHUB_SHA", "")
    if len(sys.argv) != 2 or sys.argv[1] not in ("plan", "publish", "not-started") or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ReleaseError("Expected plan/publish command and release commit context")
    if sys.argv[1] == "not-started":
        plan = {"version_bump": bump, "previous_version": os.environ.get("PREVIOUS_VERSION", ""),
                "version": os.environ.get("RELEASE_VERSION", "")}
        if not stable_version(plan["previous_version"]) or next_version([plan["previous_version"]], bump) != plan:
            raise ReleaseError("Invalid planned SemVer version")
        rows = [{"service": service, "semver_image": f"{REGISTRY}/gratilo/{service}:" + plan["version"],
                 "sha_image": f"{REGISTRY}/gratilo/{service}:" + os.environ.get("IMAGE_TAG", ""),
                 "pushed": []} for service in SERVICES]
        append_file("GITHUB_STEP_SUMMARY", summary(plan, commit, "NICHT GESTARTET – Publish-Schritt übersprungen; keine Images veröffentlicht", rows))
        return
    github = GitHub(os.environ.get("GH_TOKEN"), os.environ.get("GITHUB_REPOSITORY"))
    if sys.argv[1] == "plan":
        plan = next_version(github.tags(), bump)
        check_version(github, plan)
        append_file("GITHUB_OUTPUT", "".join(f"{key}={value}\n" for key, value in plan.items()))
        append_file("GITHUB_STEP_SUMMARY", summary(plan, commit, "GEPLANT – Veröffentlichung und Freigabe stehen aus"))
    else:
        plan = {"version_bump": bump, "previous_version": os.environ.get("PREVIOUS_VERSION", ""),
                "version": os.environ.get("RELEASE_VERSION", "")}
        rows = publish(github, ECR(os.environ), plan, commit, os.environ.get("IMAGE_TAG", ""),
                       lambda text: append_file("GITHUB_STEP_SUMMARY", text))
        append_file("GITHUB_OUTPUT", "".join(f"{row['service'].replace('-', '_')}_image={row['semver_image'].rsplit(':', 1)[0]}@{row['digest']}\n" for row in rows))


if __name__ == "__main__":
    try:
        main()
    except ReleaseError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
