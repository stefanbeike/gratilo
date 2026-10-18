import contextlib
import io
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "deployment/scripts"))
import validate_release_configuration as config


class ReleaseConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.env = {
            "ECR_PUBLISH_ENABLED": "true",
            "AWS_ACCOUNT_ID": "070351676632",
            "AWS_REGION": "eu-central-1",
            "AWS_DEPLOY_ROLE_ARN": "arn:aws:iam::070351676632:role/gratilo-github-actions-role",
        }

    def test_valid_release_and_production_configuration(self):
        for stage in ("release", "production"):
            self.assertEqual([], config.configuration_errors(stage, self.env))

    def test_failed_run_context_reports_missing_aws_not_enabled_switch(self):
        env = {**self.env, "AWS_ACCOUNT_ID": "", "AWS_DEPLOY_ROLE_ARN": ""}
        errors = config.configuration_errors("release", env)
        self.assertEqual(2, len(errors))
        self.assertTrue(any("AWS_ACCOUNT_ID is missing" in message for message in errors))
        self.assertTrue(any("AWS_DEPLOY_ROLE_ARN is missing" in message for message in errors))
        self.assertTrue(all("Environments > release" in message for message in errors))
        self.assertTrue(all("ECR_PUBLISH_ENABLED" not in message for message in errors))

    def test_flag_is_required_and_exact_true_with_no_implicit_enablement(self):
        for flag in (None, "", "false", "TRUE", "true ", "1"):
            env = {**self.env, "ECR_PUBLISH_ENABLED": flag}
            self.assertTrue(any("ECR_PUBLISH_ENABLED" in message for message in config.configuration_errors("release", env)))
        env = {k: v for k, v in self.env.items() if k != "ECR_PUBLISH_ENABLED"}
        self.assertEqual([], config.configuration_errors("production", env))

    def test_reports_all_missing_variables_without_region_default(self):
        errors = config.configuration_errors("release", {})
        self.assertEqual(4, len(errors))
        for name in self.env:
            self.assertTrue(any(name in error for error in errors))

    def test_invalid_account_region_or_role_cannot_reach_oidc(self):
        for name, value in (
            ("AWS_ACCOUNT_ID", "123456789012"), ("AWS_ACCOUNT_ID", " 070351676632"),
            ("AWS_REGION", "eu-north-1"), ("AWS_REGION", "eu-central-1 "),
            ("AWS_DEPLOY_ROLE_ARN", "arn:aws:iam::123456789012:role/other"),
            ("AWS_DEPLOY_ROLE_ARN", "arn:aws:iam::070351676632:user/other"),
            ("AWS_DEPLOY_ROLE_ARN", "arn:aws:iam::070351676632:role/other\n"),
        ):
            with self.subTest(name=name, value=value):
                self.assertTrue(config.configuration_errors("release", {**self.env, name: value}))

    def test_release_can_use_dedicated_ecr_role_but_production_role_remains_fixed(self):
        env = {**self.env, "AWS_DEPLOY_ROLE_ARN": "arn:aws:iam::070351676632:role/gratilo-ecr-publisher"}
        self.assertEqual([], config.configuration_errors("release", env))
        self.assertTrue(config.configuration_errors("production", env))

    def test_cli_fails_closed_with_annotations_and_does_not_print_values(self):
        marker = "sensitive-marker%0A::warning::must-not-print"
        for stage in ("release", "production"):
            stdout, stderr = io.StringIO(), io.StringIO()
            with patch.dict(os.environ, {name: marker for name in self.env}, clear=True), \
                    contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                self.assertEqual(1, config.main([stage]))
            output = stdout.getvalue() + stderr.getvalue()
            self.assertNotIn(marker, output)
            self.assertNotIn("must-not-print", output)
            self.assertIn("::error title=Gratilo configuration::AWS_ACCOUNT_ID", output)
            self.assertIn("::error title=Gratilo configuration::AWS_REGION", output)
            self.assertIn("::error title=Gratilo configuration::AWS_DEPLOY_ROLE_ARN", output)

    def test_success_suppresses_configuration_values(self):
        stdout = io.StringIO()
        with patch.dict(os.environ, self.env, clear=True), contextlib.redirect_stdout(stdout):
            self.assertEqual(0, config.main(["release"]))
        for value in self.env.values():
            self.assertNotIn(value, stdout.getvalue())


class ReleaseWorkflowWiringTest(unittest.TestCase):
    def test_repository_switch_is_passed_before_entering_release_environment(self):
        caller = (ROOT / ".github/workflows/release.yml").read_text()
        publisher = (ROOT / ".github/workflows/007_deploy_ecs.yml").read_text()
        self.assertIn("ecr_publish_enabled: ${{ vars.ECR_PUBLISH_ENABLED }}", caller)
        self.assertIn("ECR_PUBLISH_ENABLED: ${{ inputs.ecr_publish_enabled }}", publisher)
        self.assertNotIn("vars.ECR_PUBLISH_ENABLED", publisher)
        self.assertRegex(publisher, r"ecr_publish_enabled:\s+description:[^\n]+\s+type: string\s+required: true")
        self.assertNotIn("vars.AWS_REGION ||", publisher)

    def test_publish_and_production_gates_stay_separate_and_before_aws(self):
        publisher = (ROOT / ".github/workflows/007_deploy_ecs.yml").read_text()
        deploy = (ROOT / ".github/workflows/deploy-production.yml").read_text()
        self.assertIn("environment: release", publisher)
        self.assertIn("environment: production", deploy)
        self.assertLess(publisher.index("validate_release_configuration.py release"), publisher.index("aws-actions/configure-aws-credentials"))
        self.assertLess(deploy.index("production_approval.py"), deploy.index("validate_release_configuration.py production"))
        self.assertLess(deploy.index("validate_release_configuration.py production"), deploy.index("aws-actions/configure-aws-credentials"))
        self.assertNotIn("deploy_ecs.sh", publisher)
        for workflow in (publisher, deploy):
            self.assertIn("role-to-assume: ${{ env.AWS_DEPLOY_ROLE_ARN }}", workflow)
            self.assertIn("id-token: write", workflow)

    def test_single_manual_entrypoint_and_full_chain_remain_connected(self):
        caller = (ROOT / ".github/workflows/release.yml").read_text()
        self.assertRegex(caller, r"images:\s+name: Build & Scan\s+needs: ci")
        self.assertRegex(caller, r"publish:\s+name: Publish ECR\s+needs: \[version, images\]")
        self.assertRegex(caller, r"deploy:\s+name: Deploy Production\s+needs: \[version, images, publish\]")
        for filename in ("007_deploy_ecs.yml", "deploy-production.yml"):
            self.assertNotIn("workflow_dispatch:", (ROOT / ".github/workflows" / filename).read_text())


if __name__ == "__main__":
    unittest.main()
