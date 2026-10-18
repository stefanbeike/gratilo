import copy
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "deployment/scripts"))
import resolve_release_images as selection
import semver_release as release
import verify_ecs_health as health
from test_semver_release import FakeECR, COMMIT, SHA_TAG


class TagReleaseTest(unittest.TestCase):
    def setUp(self):
        self.github = Mock()
        self.github.tags.return_value = ["v0.0.1", "v0.0.2", "v0.0.3-beta.1"]
        self.github.tag_commit.return_value = COMMIT
        self.ecr = FakeECR([])
        self.plan = release.tag_plan(self.github, "v0.0.2", COMMIT)

    def test_existing_tag_publishes_without_git_writes_and_cannot_repeat(self):
        summaries = []
        release.publish(self.github, self.ecr, self.plan, COMMIT, SHA_TAG, summaries.append, existing_tag=True)
        self.assertEqual(6, self.ecr.pushes)
        self.github.create_tag.assert_not_called()
        with self.assertRaises(release.ReleaseError):
            release.publish(self.github, self.ecr, self.plan, COMMIT, SHA_TAG, summaries.append, existing_tag=True)
        self.assertEqual(6, self.ecr.pushes)

    def test_invalid_or_moved_tag_never_publishes(self):
        for tag in ("v01.2.3", "v1.2.3-rc.1", "v1.2.3+meta", "1.2.3", "v1.2.3\n"):
            with self.assertRaises(release.ReleaseError):
                release.tag_plan(self.github, tag, COMMIT)
        self.github.tag_commit.return_value = "b" * 40
        with self.assertRaises(release.ReleaseError):
            release.publish(self.github, self.ecr, self.plan, COMMIT, SHA_TAG, lambda _: None, existing_tag=True)
        self.assertEqual(0, self.ecr.pushes)

    def test_first_tag_plan_and_numeric_predecessor(self):
        self.github.tags.return_value = ["v1.9.0", "v1.10.0", "v1.11.0", "v1.12.0-rc.1"]
        self.assertEqual("v1.10.0", release.tag_plan(self.github, "v1.11.0", COMMIT)["previous_version"])
        self.github.tags.return_value = ["v0.0.1"]
        self.assertEqual("v0.0.0", release.tag_plan(self.github, "v0.0.1", COMMIT)["previous_version"])

    def test_annotated_and_lightweight_tags_resolve(self):
        client = release.GitHub("test-token", "stefanbeike/gratilo")
        for responses in ([{"object": {"type": "commit", "sha": COMMIT}}],
                          [{"object": {"type": "tag", "sha": "b" * 40}}, {"object": {"type": "commit", "sha": COMMIT}}]):
            with patch.object(client, "request", side_effect=responses):
                self.assertEqual(COMMIT, client.tag_commit("v0.0.2"))

    def test_non_main_commit_cannot_start_tag_release(self):
        env = {"GITHUB_SHA": COMMIT, "GITHUB_REF": "refs/tags/v0.0.2", "GITHUB_REF_NAME": "v0.0.2"}
        with patch.dict(release.os.environ, env, clear=True), patch.object(release.sys, "argv", ["script", "tag-plan"]), \
                patch.object(release, "GitHub", return_value=self.github), \
                patch.object(release.subprocess, "run", return_value=Mock(returncode=1)):
            with self.assertRaisesRegex(release.ReleaseError, "main history"):
                release.main()


class ReleaseSelectionTest(unittest.TestCase):
    def setUp(self):
        self.details = {name: {"imageDetails": [{"imageDigest": "sha256:" + str(i) * 64,
                         "imageTags": ["v0.0.2", SHA_TAG]}]} for i, name in enumerate(release.SERVICES, 1)}

    def select(self, version="v0.0.2", account="070351676632"):
        return selection.resolve(version, account, "eu-central-1", lambda name, _: self.details[name])

    def test_all_three_images_are_pinned_to_digests(self):
        images, provenance = self.select()
        self.assertEqual(3, len(images))
        self.assertEqual(SHA_TAG, provenance)
        self.assertTrue(all("@sha256:" in image for image in images.values()))

    def test_missing_image_wrong_tag_digest_or_mixed_build_rejected(self):
        original = copy.deepcopy(self.details)
        for value in ({"imageDetails": []}, {"imageDetails": [{"imageDigest": "bad", "imageTags": ["v0.0.2", SHA_TAG]}]},
                      {"imageDetails": [{"imageDigest": "sha256:" + "a" * 64, "imageTags": [SHA_TAG]}]},
                      {"imageDetails": [{"imageDigest": "sha256:" + "a" * 64, "imageTags": ["v0.0.2", SHA_TAG[:-1] + "2"]}]}):
            self.details = copy.deepcopy(original)
            self.details["payment-service"] = value
            with self.assertRaises(ValueError):
                self.select()

    def test_invalid_version_and_account_fail_before_aws_query(self):
        describe = Mock()
        for version, account in (("latest", "070351676632"), ("v1.0.0;echo", "070351676632"), ("v1.0.0", "999999999999")):
            with self.assertRaises(ValueError):
                selection.resolve(version, account, "eu-central-1", describe)
        describe.assert_not_called()

    def test_ecr_commit_must_match_git_release_tag(self):
        with self.assertRaisesRegex(ValueError, "Git release tag"):
            selection.resolve("v0.0.2", "070351676632", "eu-central-1",
                              lambda name, _: self.details[name], expected_commit="b" * 40)
        images, _ = selection.resolve("v0.0.2", "070351676632", "eu-central-1",
                                     lambda name, _: self.details[name], expected_commit=COMMIT)
        self.assertEqual(3, len(images))

    def test_health_rejects_unhealthy_or_other_revision(self):
        response = {"tasks": [{"taskDefinitionArn": "selected", "lastStatus": "RUNNING", "containers": [
            {"name": name, "lastStatus": "RUNNING", "healthStatus": "HEALTHY"} for name in release.SERVICES]}]}
        health.validate_tasks(response, "selected", 1)
        with self.assertRaises(ValueError):
            health.validate_tasks(response, "other", 1)
        response["tasks"][0]["containers"][0]["healthStatus"] = "UNHEALTHY"
        with self.assertRaises(ValueError):
            health.validate_tasks(response, "selected", 1)
        for states in ([], ["unhealthy"], ["healthy", "unhealthy"]):
            with self.assertRaises(ValueError):
                health.validate_targets({"TargetHealthDescriptions": [{"TargetHealth": {"State": state}} for state in states]})


if __name__ == "__main__":
    unittest.main()
