import importlib.util
import io
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("semver_release", ROOT / "deployment/scripts/semver_release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
COMMIT = "a" * 40
SHA_TAG = "sha-" + COMMIT + "-123-1"


class FakeGitHub:
    def __init__(self, events, tags=()):
        self.events, self.names = events, list(tags)
        self.checks, self.conflict_at, self.fail_create = 0, None, False
        self.created = None

    def tags(self):
        return list(self.names)

    def tag_exists(self, version):
        self.checks += 1
        if self.checks == self.conflict_at:
            self.names.append(version)
        self.events.append(("check-git", version))
        return version in self.names

    def create_tag(self, version, commit):
        self.events.append(("create-git", version, commit))
        if self.fail_create:
            raise release.GitHubError(403)
        if version in self.names:
            raise release.GitHubError(422)
        self.names.append(version)
        self.created = (version, commit)


class FakeECR:
    def __init__(self, events):
        self.events, self.digests = events, {}
        self.mutable, self.failure_at, self.mismatch, self.pushes = None, None, False, 0
        self.race = False

    def image(self, service, tag):
        return "registry/gratilo/" + service + ":" + tag

    def require_immutable(self, service):
        if service == self.mutable:
            raise release.ReleaseError("Repository is mutable")

    def digest(self, service, tag):
        value = self.digests.get((service, tag))
        if self.mismatch and value and tag.startswith("v"):
            return "sha256:" + "f" * 64
        return value

    def prepare(self, service, sha_tag, version):
        self.events.append(("prepare", service))

    def push(self, service, tag):
        self.pushes += 1
        self.events.append(("push", service, tag))
        if self.race:
            self.digests[(service, tag)] = "sha256:" + "b" * 64
        if self.pushes == self.failure_at:
            raise release.ReleaseError("Simulated push failure")
        if (service, tag) in self.digests:
            raise release.ReleaseError("Immutable ECR tag exists")
        self.digests[(service, tag)] = "sha256:" + str(release.SERVICES.index(service) + 1) * 64


class VersionTest(unittest.TestCase):
    def test_first_release_uses_zero_as_base(self):
        for bump, version in [("patch", "v0.0.1"), ("minor", "v0.1.0"), ("major", "v1.0.0")]:
            self.assertEqual({"previous_version": "v0.0.0", "version": version, "version_bump": bump},
                             release.next_version([], bump))

    def test_numeric_maximum_and_bumps(self):
        tags = ["v1.9.99", "v1.10.3", "v1.2.99", "v0.99.99"]
        for bump, version in [("patch", "v1.10.4"), ("minor", "v1.11.0"), ("major", "v2.0.0")]:
            actual = release.next_version(tags, bump)
            self.assertEqual("v1.10.3", actual["previous_version"])
            self.assertEqual(version, actual["version"])

    def test_ignores_prerelease_metadata_noncanonical_and_other_tags(self):
        tags = ["v1.2.3", "v9.0.0-rc.1", "v99.0.0+build.1", "v01.2.3", "v1.02.3",
                "v1.2.03", "v2.0", "2.0.0", "V9.9.9", "sha-deadbeef", "v4.0.0\n"]
        self.assertEqual("v1.2.4", release.next_version(tags, "patch")["version"])
        with self.assertRaises(release.ReleaseError):
            release.next_version(tags, "unknown")

    def test_all_git_tag_pages_are_read(self):
        github = release.GitHub("unit-test-token", "stefanbeike/gratilo")
        pages = [[{"name": "v0.0." + str(i)} for i in range(100)], [{"name": "v12.3.4"}]]
        with patch.object(github, "request", side_effect=pages) as request:
            self.assertEqual("v12.3.5", release.next_version(github.tags(), "patch")["version"])
            self.assertEqual("tags?per_page=100&page=2", request.call_args.args[1])


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.events, self.summaries = [], []
        self.github = FakeGitHub(self.events, ["v1.2.3"])
        self.ecr = FakeECR(self.events)
        self.plan = release.next_version(self.github.tags(), "patch")

    def run_publish(self, sha_tag=SHA_TAG):
        return release.publish(self.github, self.ecr, self.plan, COMMIT, sha_tag, self.summaries.append)

    def test_two_tags_share_each_digest_and_git_tag_is_last(self):
        rows = self.run_publish()
        pushes = [event for event in self.events if event[0] == "push"]
        self.assertEqual(6, len(pushes))
        self.assertEqual(("v1.2.4", COMMIT), self.github.created)
        self.assertEqual("create-git", self.events[-1][0])
        self.assertEqual(3, self.github.checks)
        first_push = next(i for i, event in enumerate(self.events) if event[0] == "push")
        self.assertEqual("check-git", self.events[first_push - 1][0])
        for row in rows:
            self.assertEqual(self.ecr.digest(row["service"], SHA_TAG), self.ecr.digest(row["service"], "v1.2.4"))
            self.assertIn(row["digest"], self.summaries[-1])
            self.assertIn(row["semver_image"], self.summaries[-1])
            self.assertIn(row["sha_image"], self.summaries[-1])
        self.assertIn("ERFOLGREICH", self.summaries[-1])

    def test_full_first_publication(self):
        self.github.names = []
        self.plan = release.next_version([], "minor")
        self.run_publish()
        self.assertEqual(("v0.1.0", COMMIT), self.github.created)

    def test_existing_git_tag_aborts_before_any_write(self):
        self.github.names.append("v1.2.4")
        with self.assertRaises(release.ReleaseError):
            self.run_publish()
        self.assertEqual(0, self.ecr.pushes)

    def test_existing_ecr_tags_in_any_repository_abort_all_pushes(self):
        for service in release.SERVICES:
            for tag in (SHA_TAG, "v1.2.4"):
                with self.subTest(service=service, tag=tag):
                    self.ecr.digests = {(service, tag): "sha256:" + "b" * 64}
                    with self.assertRaises(release.ReleaseError):
                        self.run_publish()
                    self.assertEqual(0, self.ecr.pushes)
                    self.assertIsNone(self.github.created)

    def test_git_history_advanced_since_planning_aborts(self):
        self.github.names.append("v2.0.0")
        with self.assertRaisesRegex(release.ReleaseError, "history changed"):
            self.run_publish()
        self.assertEqual(0, self.ecr.pushes)

    def test_git_conflict_immediately_before_first_push_aborts(self):
        self.github.conflict_at = 2
        with self.assertRaises(release.ReleaseError):
            self.run_publish()
        self.assertEqual(0, self.ecr.pushes)

    def test_mutable_repository_is_not_accepted(self):
        self.ecr.mutable = "payment-service"
        with self.assertRaises(release.ReleaseError):
            self.run_publish()
        self.assertEqual(0, self.ecr.pushes)

    def test_partial_push_failure_never_creates_git_tag(self):
        self.ecr.failure_at = 4
        with self.assertRaisesRegex(release.ReleaseError, "TEILVERÖFFENTLICHUNG"):
            self.run_publish()
        self.assertIsNone(self.github.created)
        self.assertIn("nicht bestätigt", self.summaries[-1])

    def test_retry_after_partial_publication_does_not_overwrite(self):
        self.ecr.failure_at = 3
        with self.assertRaises(release.ReleaseError):
            self.run_publish()
        before = dict(self.ecr.digests)
        self.ecr.failure_at = None
        self.ecr.pushes = 0
        with self.assertRaisesRegex(release.ReleaseError, "ECR tag exists"):
            self.run_publish()
        self.assertEqual(before, self.ecr.digests)
        self.assertEqual(0, self.ecr.pushes)

    def test_successful_run_cannot_be_republished_with_same_plan(self):
        self.run_publish()
        before = dict(self.ecr.digests)
        self.ecr.pushes = 0
        with self.assertRaisesRegex(release.ReleaseError, "Git tag already exists"):
            self.run_publish()
        self.assertEqual(0, self.ecr.pushes)
        self.assertEqual(before, self.ecr.digests)

    def test_new_complete_run_calculates_next_version_and_new_sha_tag(self):
        self.run_publish()
        self.plan = release.next_version(self.github.tags(), "patch")
        self.run_publish(SHA_TAG[:-1] + "2")
        self.assertEqual(("v1.2.5", COMMIT), self.github.created)

    def test_git_tag_protection_failure_reports_all_pushed_images(self):
        self.github.fail_create = True
        with self.assertRaisesRegex(release.ReleaseError, "TEILVERÖFFENTLICHUNG"):
            self.run_publish()
        self.assertEqual(6, self.ecr.pushes)
        self.assertIn("HTTP 403", self.summaries[-1])
        self.assertIsNone(self.github.created)

    def test_external_git_race_after_ecr_pushes_does_not_overwrite_tag(self):
        self.github.conflict_at = 3
        with self.assertRaisesRegex(release.ReleaseError, "TEILVERÖFFENTLICHUNG"):
            self.run_publish()
        self.assertEqual(6, self.ecr.pushes)
        self.assertIsNone(self.github.created)

    def test_immutable_ecr_rejects_external_race(self):
        self.ecr.race = True
        with self.assertRaises(release.ReleaseError):
            self.run_publish()
        self.assertIsNone(self.github.created)

    def test_digest_mismatch_blocks_git_tag(self):
        self.ecr.mismatch = True
        with self.assertRaisesRegex(release.ReleaseError, "same confirmed digest"):
            self.run_publish()
        self.assertIsNone(self.github.created)

    def test_wrong_commit_image_tag_aborts_without_writes(self):
        with self.assertRaises(release.ReleaseError):
            self.run_publish("sha-" + "b" * 40 + "-123-1")
        self.assertEqual(0, self.ecr.pushes)


class ClientSafetyTest(unittest.TestCase):
    def test_git_tag_uses_create_only_ref_api(self):
        github = release.GitHub("unit-test-token", "stefanbeike/gratilo")
        tag_sha = "b" * 40
        responses = [{"sha": tag_sha},
                     {"ref": "refs/tags/v1.2.4", "object": {"sha": tag_sha, "type": "tag"}},
                     {"object": {"sha": tag_sha, "type": "tag"}},
                     {"object": {"sha": COMMIT, "type": "commit"}}]
        with patch.object(github, "request", side_effect=responses) as request:
            github.create_tag("v1.2.4", COMMIT)
            self.assertEqual("git/tags", request.call_args_list[0].args[1])
            self.assertEqual({"ref": "refs/tags/v1.2.4", "sha": tag_sha}, request.call_args_list[1].args[2])
            self.assertTrue(all(call.args[0] in ("POST", "GET") for call in request.call_args_list))

    def test_github_errors_do_not_print_token_or_response_body(self):
        github = release.GitHub("secret-unit-test-token", "stefanbeike/gratilo")
        error = HTTPError("https://api.github.com/test", 403, "secret-error-body", {}, io.BytesIO(b"secret-error-body"))
        with patch.object(release, "urlopen", side_effect=error):
            with self.assertRaises(release.GitHubError) as raised:
                github.request("POST", "git/refs", {})
        self.assertNotIn("secret-", str(raised.exception))

    def test_ecr_access_denied_is_not_treated_as_absent(self):
        ecr = release.ECR({"AWS_ACCOUNT_ID": "070351676632", "AWS_REGION": "eu-central-1", "RUNNER_TEMP": "/tmp"})
        with patch.object(ecr, "aws", return_value=SimpleNamespace(returncode=254, stdout="", stderr="(AccessDeniedException)")):
            with self.assertRaises(release.ReleaseError):
                ecr.digest("frontend", "v1.2.4")
        with patch.object(ecr, "aws", return_value=SimpleNamespace(returncode=254, stdout="", stderr="(ImageNotFoundException)")):
            self.assertIsNone(ecr.digest("frontend", "v1.2.4"))

class CommandTest(unittest.TestCase):
    def test_plan_writes_outputs_and_summary_without_publication(self):
        from tempfile import TemporaryDirectory
        with TemporaryDirectory() as folder:
            output, summary = Path(folder) / "output", Path(folder) / "summary"
            env = {"GH_TOKEN": "unit-test-token", "GITHUB_REPOSITORY": "stefanbeike/gratilo",
                   "GITHUB_SHA": COMMIT, "VERSION_BUMP": "patch", "GITHUB_OUTPUT": str(output),
                   "GITHUB_STEP_SUMMARY": str(summary)}
            github = FakeGitHub([], ["v1.9.9", "v1.10.0"])
            with patch.dict(release.os.environ, env, clear=True), patch.object(release.sys, "argv", ["script", "plan"]), \
                    patch.object(release, "GitHub", return_value=github), patch.object(release, "ECR") as ecr:
                release.main()
                ecr.assert_not_called()
            self.assertIn("version=v1.10.1", output.read_text())
            self.assertIn("GEPLANT", summary.read_text())
            self.assertIsNone(github.created)

    def test_skipped_publication_summary_requires_no_token_and_no_network(self):
        from tempfile import TemporaryDirectory
        with TemporaryDirectory() as folder:
            summary = Path(folder) / "summary"
            env = {"GITHUB_SHA": COMMIT, "VERSION_BUMP": "patch", "PREVIOUS_VERSION": "v0.0.0",
                   "RELEASE_VERSION": "v0.0.1", "IMAGE_TAG": SHA_TAG, "GITHUB_STEP_SUMMARY": str(summary)}
            with patch.dict(release.os.environ, env, clear=True), patch.object(release.sys, "argv", ["script", "not-started"]), \
                    patch.object(release, "GitHub") as github, patch.object(release, "ECR") as ecr:
                release.main()
                github.assert_not_called()
                ecr.assert_not_called()
            self.assertIn("NICHT GESTARTET", summary.read_text())
            for service in release.SERVICES:
                self.assertIn("gratilo/" + service + ":v0.0.1", summary.read_text())


if __name__ == "__main__":
    unittest.main()
