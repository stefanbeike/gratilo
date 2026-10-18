from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "deployment/scripts"))
import semver_release as release
import production_approval as approval
import resolve_release_images as selection
from test_semver_release import FakeGitHub, FakeECR, COMMIT, SHA_TAG


class CombinedReleaseTest(unittest.TestCase):
    def setUp(self):
        self.github = FakeGitHub([])
        self.github.main_commit = Mock(return_value=COMMIT)
        self.ecr = FakeECR([])
        self.plan = release.next_version([], "patch")

    def publish(self):
        return release.publish(self.github, self.ecr, self.plan, COMMIT, SHA_TAG,
                               lambda _: None, verify_main=True)

    def test_main_moves_before_push_prevents_all_writes(self):
        self.github.main_commit.side_effect = [COMMIT, "b" * 40]
        with self.assertRaisesRegex(release.ReleaseError, "main advanced"):
            self.publish()
        self.assertEqual(0, self.ecr.pushes)
        self.assertIsNone(self.github.created)

    def test_main_moves_after_push_reports_partial_publication(self):
        self.github.main_commit.side_effect = [COMMIT, COMMIT, "b" * 40]
        with self.assertRaisesRegex(release.ReleaseError, "TEILVERÖFFENTLICHUNG"):
            self.publish()
        self.assertEqual(6, self.ecr.pushes)
        self.assertIsNone(self.github.created)

    def test_release_plan_is_read_only_and_checks_exact_main_and_confirmation(self):
        for event, ref, confirmation, allowed in [
            ("workflow_dispatch", "refs/heads/main", "RELEASE DEPLOY gratilo-production", True),
            ("push", "refs/heads/main", "RELEASE DEPLOY gratilo-production", False),
            ("workflow_dispatch", "refs/heads/feature", "RELEASE DEPLOY gratilo-production", False),
            ("workflow_dispatch", "refs/heads/main", "RELEASE DEPLOY gratilo-production ", False),
        ]:
            with self.subTest(event=event, ref=ref, confirmation=confirmation), tempfile.TemporaryDirectory() as folder:
                env = {"GITHUB_SHA": COMMIT, "GITHUB_EVENT_NAME": event, "GITHUB_REF": ref,
                       "CONFIRMATION": confirmation, "VERSION_BUMP": "patch", "GITHUB_OUTPUT": folder + "/output"}
                with patch.dict(release.os.environ, env, clear=True), \
                        patch.object(release.sys, "argv", ["script", "release-plan"]), \
                        patch.object(release, "GitHub", return_value=self.github), patch.object(release, "ECR") as ecr:
                    if allowed:
                        release.main()
                        self.assertIn("version=v0.0.1", Path(folder, "output").read_text())
                    else:
                        with self.assertRaises(release.ReleaseError):
                            release.main()
                    ecr.assert_not_called()
                self.assertIsNone(self.github.created)

    def test_release_publish_outputs_all_digests_and_creates_tag_after_push(self):
        with tempfile.TemporaryDirectory() as folder:
            env = {"GITHUB_SHA": COMMIT, "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/main",
                   "VERSION_BUMP": "patch", "PREVIOUS_VERSION": "v0.0.0", "RELEASE_VERSION": "v0.0.1",
                   "IMAGE_TAG": SHA_TAG, "GITHUB_OUTPUT": folder + "/output"}
            with patch.dict(release.os.environ, env, clear=True), patch.object(release.sys, "argv", ["script", "release-publish"]), \
                    patch.object(release, "GitHub", return_value=self.github), patch.object(release, "ECR", return_value=self.ecr):
                release.main()
            self.assertEqual(6, self.ecr.pushes)
            self.assertEqual(("v0.0.1", COMMIT), self.github.created)
            output = Path(folder, "output").read_text()
            for name in ("frontend", "user_service", "payment_service"):
                self.assertIn(name + "_image=", output)
            self.assertEqual(3, output.count("@sha256:"))

    def test_annotated_ref_conflict_never_uses_update_or_delete(self):
        github = release.GitHub("test-token", "stefanbeike/gratilo")
        with patch.object(github, "request", side_effect=[{"sha": "b" * 40}, release.GitHubError(422)]) as request:
            with self.assertRaises(release.GitHubError):
                github.create_tag("v0.0.1", COMMIT)
            self.assertEqual(["POST", "POST"], [call.args[0] for call in request.call_args_list])

    def test_deploy_accepts_only_exact_publish_digests_and_build(self):
        images = {name: release.REGISTRY + "/gratilo/" + name + "@sha256:" + str(i) * 64
                  for i, name in enumerate(release.SERVICES, 1)}
        env = {"EXPECTED_BUILD_TAG": SHA_TAG, **{
            "EXPECTED_" + name.upper().replace("-", "_") + "_IMAGE": image for name, image in images.items()}}
        selection.verify_publish_outputs(images, SHA_TAG, env)
        for mutate in ({**env, "EXPECTED_BUILD_TAG": SHA_TAG[:-1] + "2"},
                       {**env, "EXPECTED_USER_SERVICE_IMAGE": images["frontend"]},
                       {key: value for key, value in env.items() if key != "EXPECTED_PAYMENT_SERVICE_IMAGE"}):
            with self.assertRaises(ValueError):
                selection.verify_publish_outputs(images, SHA_TAG, mutate)


class ProductionApprovalTest(unittest.TestCase):
    def setUp(self):
        self.env = {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/main",
                    "GITHUB_SHA": COMMIT, "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1",
                    "RELEASE_ACTOR": "operator", "TRIGGERING_ACTOR": "operator"}
        self.run = {"event": "workflow_dispatch", "head_branch": "main", "head_sha": COMMIT,
                    "path": ".github/workflows/release.yml", "id": 123, "run_attempt": 1,
                    "actor": {"login": "operator"}, "triggering_actor": {"login": "operator"}}
        self.review = {"state": "approved", "environments": [{"name": "production"}],
                       "user": {"type": "User", "login": "independent-reviewer"}}

    def test_independent_human_review_is_accepted(self):
        approval.validate_approval(self.run, [self.review], self.env)

    def test_absent_bot_release_environment_or_rejection_fails_closed(self):
        variants = [[],
                    [{**self.review, "user": {"type": "Bot", "login": "review-bot"}}],
                    [{**self.review, "environments": [{"name": "release"}]}],
                    [{**self.review, "state": "rejected"}]]
        for reviews in variants:
            with self.subTest(reviews=reviews), self.assertRaises(release.ReleaseError):
                approval.validate_approval(self.run, reviews, self.env)

    def test_wrong_run_attempt_commit_workflow_or_trigger_fails_closed(self):
        for key, value in (("run_attempt", 2), ("head_sha", "b" * 40), ("event", "push"),
                           ("path", ".github/workflows/another.yml"), ("head_branch", "feature"), ("id", 124)):
            with self.subTest(key=key), self.assertRaises(release.ReleaseError):
                approval.validate_approval({**self.run, key: value}, [self.review], self.env)

    def test_original_starter_can_approve_own_production_deployment(self):
        review = {**self.review, "user": {"type": "User", "login": "operator"}}
        approval.validate_approval(self.run, [review], self.env)

    def test_retry_starter_can_approve_own_production_deployment(self):
        approval.validate_approval(self.run, [self.review], {**self.env, "TRIGGERING_ACTOR": "independent-reviewer"})

    def test_self_approval_cannot_bypass_rejection_or_wrong_environment(self):
        review = {**self.review, "user": {"type": "User", "login": "operator"}}
        for invalid in ({**review, "state": "rejected"},
                        {**review, "environments": [{"name": "release"}]},
                        {**review, "user": {"type": "Bot", "login": "operator"}}):
            with self.assertRaises(release.ReleaseError):
                approval.validate_approval(self.run, [invalid], self.env)

    def test_api_error_is_not_treated_as_approval(self):
        with patch.dict(approval.os.environ, self.env, clear=True), patch.object(approval, "GitHub") as github:
            github.return_value.request.side_effect = release.GitHubError(403)
            with self.assertRaises(release.GitHubError):
                approval.main()


if __name__ == "__main__":
    unittest.main()
