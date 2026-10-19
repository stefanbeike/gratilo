import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]

def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "deployment/scripts" / (name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result

setup = module("prepare_stripe_test")
build = module("validate_stripe_test_build")

class StripeTestSetupTest(unittest.TestCase):
    def test_secret_validation_never_echoes_values(self):
        marker = "sensitive-private-fixture-do-not-print"
        for payload in [{}, {"SecretString": marker}, {"SecretString": json.dumps({"secret_key": marker})},
                        {"SecretString": json.dumps({"secret_key": "sk_live_" + marker, "webhook_secret": "whsec_" + marker})}]:
            with self.assertRaises(setup.AuditError) as error:
                setup.validate_secret(payload)
            self.assertNotIn(marker, str(error.exception))
        good = {"secret_key": "sk_test_fixture", "webhook_secret": "whsec_fixture"}
        self.assertEqual(good, setup.validate_secret({"SecretString": json.dumps(good)}))

    def test_policy_is_exact_and_custom_kms_is_conditioned(self):
        arn = "arn:aws:secretsmanager:eu-central-1:070351676632:secret:gratilo/prd/stripe-test-Ab1234"
        policy = setup.execution_policy(arn)
        self.assertEqual(1, len(policy["Statement"]))
        self.assertEqual(arn, policy["Statement"][0]["Resource"])
        self.assertEqual("secretsmanager:GetSecretValue", policy["Statement"][0]["Action"])
        kms = {"KeyManager": "CUSTOMER", "Arn": "arn:aws:kms:eu-central-1:070351676632:key/fixture"}
        decrypt = setup.execution_policy(arn, kms)["Statement"][1]
        self.assertEqual(kms["Arn"], decrypt["Resource"])
        self.assertEqual(arn, decrypt["Condition"]["StringEquals"]["kms:EncryptionContext:SecretARN"])
        self.assertEqual("secretsmanager.eu-central-1.amazonaws.com", decrypt["Condition"]["StringEquals"]["kms:ViaService"])

    def test_task_overlay_preserves_existing_settings_and_wires_exact_json_keys(self):
        task = json.loads((ROOT / "deployment/ecs/gratilo-prd-app.task-definition.json").read_text())
        original = copy.deepcopy(task)
        arn = "arn:aws:secretsmanager:eu-central-1:070351676632:secret:gratilo/prd/stripe-test-Ab1234"
        result = setup.task_candidate(task, arn, ["11111111-1111-1111-1111-111111111111"])
        self.assertEqual(original, task)
        self.assertEqual(task["executionRoleArn"], result["executionRoleArn"])
        for before, after in zip(task["containerDefinitions"], result["containerDefinitions"]):
            for field in ("image", "healthCheck", "logConfiguration", "portMappings"):
                self.assertEqual(before[field], after[field])
            if after["name"] != "payment-service":
                self.assertEqual(before, after)
                continue
            env = {e["name"]: e["value"] for e in after["environment"]}
            self.assertEqual("true", env["PAYMENTS_ENABLED"])
            self.assertEqual("true", env["STRIPE_TEST_ONLY"])
            self.assertEqual("true", env["STRIPE_SANDBOX_PAYOUTS"])
            self.assertEqual("false", env["FEATURE_TIP_POOLS_ENABLED"])
            refs = {s["name"]: s["valueFrom"] for s in after["secrets"]}
            self.assertEqual(arn + ":secret_key::", refs["STRIPE_SECRET_KEY"])
            self.assertEqual(arn + ":webhook_secret::", refs["STRIPE_WEBHOOK_SECRET"])
            for entry in before["secrets"]:
                self.assertEqual(entry["valueFrom"], refs[entry["name"]])

    def test_self_activation_secret_reference_avoids_manual_uuid_configuration(self):
        task = json.loads((ROOT / "deployment/ecs/gratilo-prd-app.task-definition.json").read_text())
        arn = "arn:aws:secretsmanager:eu-central-1:070351676632:secret:gratilo/prd/stripe-test-Ab1234"
        result = setup.task_candidate(task, arn, [], self_activation=True)
        payment = next(c for c in result["containerDefinitions"] if c["name"] == "payment-service")
        refs = {s["name"]: s["valueFrom"] for s in payment["secrets"]}
        env = {e["name"]: e["value"] for e in payment["environment"]}
        self.assertEqual(arn + ":activation_code::", refs["PAYMENTS_TEST_ACTIVATION_CODE"])
        self.assertNotIn("PAYMENTS_TEST_ACTIVATION_CODE", env)
        self.assertEqual("", env["PAYMENTS_TEST_USER_IDS"])
        for value in ("short", 123, "x" * 257):
            payload = {"secret_key": "sk_test_fixture", "webhook_secret": "whsec_fixture", "activation_code": value}
            with self.assertRaises(setup.AuditError):
                setup.validate_secret({"SecretString": json.dumps(payload)})
        task["containerDefinitions"][2]["environment"].append({"name": "PAYMENTS_TEST_ACTIVATION_CODE", "value": "private-marker"})
        with self.assertRaises(setup.AuditError) as error:
            setup.task_candidate(task, arn, [], self_activation=True)
        self.assertNotIn("private-marker", str(error.exception))

    def test_cloud_wrapper_suppresses_raw_responses_on_errors(self):
        output = type("Result", (), {"returncode": 254, "stdout": "sensitive-marker", "stderr": "sensitive-marker"})()
        with patch.object(setup.subprocess, "run", return_value=output), self.assertRaises(setup.AuditError) as error:
            setup.aws("secretsmanager", "get-secret-value", "--secret-id", "fixture")
        self.assertNotIn("sensitive-marker", str(error.exception))

    def test_build_requires_manual_main_run_and_only_public_test_key(self):
        build.validate({})
        good = {"STRIPE_TEST_MODE": "true", "STRIPE_TEST_PUBLISHABLE_KEY": "pk_test_fixture",
                "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/main"}
        build.validate(good)
        for key, value in [("GITHUB_EVENT_NAME", "pull_request"), ("GITHUB_REF", "refs/heads/other"),
                           ("STRIPE_TEST_PUBLISHABLE_KEY", "sk_test_private"), ("STRIPE_TEST_PUBLISHABLE_KEY", "pk_live_fixture")]:
            with self.assertRaises(ValueError):
                build.validate({**good, key: value})
        with self.assertRaises(ValueError):
            build.validate({"STRIPE_TEST_MODE": "false", "STRIPE_TEST_PUBLISHABLE_KEY": "pk_test_fixture"})

    def test_build_entrypoint_cannot_publish_deploy_or_tag(self):
        text = (ROOT / ".github/workflows/stripe-test-build.yml").read_text()
        for forbidden in ("id-token: write", "contents: write", "deploy_ecs.sh", "007_deploy_ecs", "release-publish"):
            self.assertNotIn(forbidden, text)
        self.assertIn("vars.STRIPE_TEST_PUBLISHABLE_KEY", text)
        self.assertNotIn("pk_test_", text)

if __name__ == "__main__":
    unittest.main()
