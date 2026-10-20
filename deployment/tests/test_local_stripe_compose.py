import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("local_stripe", ROOT / "docker-compose/stripe_test.py")
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)


def fixture():
    return {"STRIPE_SECRET_KEY": "sk_test_fixture", "VITE_STRIPE_PUBLISHABLE_KEY": "pk_test_fixture",
            "STRIPE_WEBHOOK_SECRET": "whsec_fixture", "FRONTEND_BASE_URL": "http://localhost:3001",
            "FEATURE_TIP_POOLS_ENABLED": "false", **{key: "a" * 64 for key in local.RANDOM_FIELDS}}


class LocalStripeConfigurationTest(unittest.TestCase):
    def test_rejects_live_missing_and_production_configuration(self):
        for changes in ({"STRIPE_SECRET_KEY": "sk_live_fixture"}, {"VITE_STRIPE_PUBLISHABLE_KEY": "pk_live_fixture"},
                        {"STRIPE_WEBHOOK_SECRET": ""}, {"FRONTEND_BASE_URL": "https://gratilo.eu"},
                        {"FRONTEND_BASE_URL": "http://example.test"}, {"FEATURE_TIP_POOLS_ENABLED": "yes"},
                        {"JWT_SECRET": "replace_me"}):
            with self.subTest(changes=list(changes)), self.assertRaises(ValueError):
                local.validate({**fixture(), **changes})

    def test_accepts_opt_in_pools_and_https_development_tunnel(self):
        local.validate({**fixture(), "FEATURE_TIP_POOLS_ENABLED": "true", "FRONTEND_BASE_URL": "https://sandbox.example.test"})

    def test_initialization_preserves_existing_file_and_separates_local_credentials(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "docker-compose").mkdir()
            (root / "docker-compose/.env.example").write_text((ROOT / "docker-compose/.env.example").read_text())
            (root / ".env").write_text("STRIPE_SECRET_KEY=sk_test_fixture\nSTRIPE_PUBLISHABLE_KEY=pk_test_fixture\nSTRIPE_WEBHOOK_SECRET=whsec_unrelated\nJWT_SECRET=unrelated\n")
            path = root / ".env.stripe-test"
            with patch.object(local, "ROOT", root), patch.object(local, "ENV_FILE", path), contextlib.redirect_stdout(io.StringIO()):
                local.initialize()
                original = path.read_bytes()
                local.initialize()
                self.assertEqual(original, path.read_bytes())
            values = local.read_env(path)
            self.assertEqual("", values["STRIPE_WEBHOOK_SECRET"])
            self.assertEqual("sk_test_fixture", values["STRIPE_SECRET_KEY"])
            self.assertEqual("pk_test_fixture", values["VITE_STRIPE_PUBLISHABLE_KEY"])
            self.assertEqual(4, len({values[key] for key in local.RANDOM_FIELDS}))

    def test_listener_secret_is_not_printed_or_passed_in_command_arguments(self):
        values = fixture()
        result = subprocess.CompletedProcess([], 0, "whsec_localfixture\n", "")
        output = io.StringIO()
        with patch.object(local.subprocess, "run", return_value=result) as run, patch.object(local, "save_env") as save, contextlib.redirect_stdout(output):
            local.webhook_secret(values)
        self.assertNotIn(values["STRIPE_SECRET_KEY"], " ".join(run.call_args.args[0]))
        self.assertNotIn("whsec_localfixture", output.getvalue())
        self.assertEqual("whsec_localfixture", save.call_args.args[0]["STRIPE_WEBHOOK_SECRET"])

    def test_shell_overrides_cannot_select_another_compose_project_or_aws_profile(self):
        with patch.dict(os.environ, {"COMPOSE_PROJECT_NAME": "production", "AWS_PROFILE": "production", "STRIPE_SECRET_KEY": "sk_live_fixture"}):
            env = local.process_env(fixture())
        self.assertNotIn("AWS_PROFILE", env)
        self.assertNotIn("COMPOSE_PROJECT_NAME", env)
        self.assertEqual("sk_test_fixture", env["STRIPE_SECRET_KEY"])


@unittest.skipUnless(shutil.which("docker"), "Docker Compose not installed")
class LocalComposeRenderingTest(unittest.TestCase):
    def test_optional_host_build_uses_only_local_backend_distributions(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / ".env.stripe-test"
            path.write_text("\n".join(key + "=" + value for key, value in fixture().items()))
            with patch.object(local, "ENV_FILE", path):
                response = local.compose(fixture(), "config", "--format", "json", capture=True, host_build=True)
        services = json.loads(response.stdout)["services"]
        for name in ("user-service", "payment-service"):
            build = services[name]["build"]
            self.assertEqual("Dockerfile.local-runtime", build["dockerfile"])
            self.assertTrue(build["additional_contexts"]["distribution"].endswith("backend/" + name + "/build/install/" + name))
            self.assertEqual("jdbc:postgresql://postgres:5432/gratilodb", services[name]["environment"]["DATASOURCES_DEFAULT_URL"])
            self.assertNotIn("STRIPE_SECRET_KEY", build["args"])

    def test_existing_compose_files_still_render_with_local_frontend_and_key(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / ".env"
            path.write_text("\n".join(key + "=" + value for key, value in fixture().items()))
            command = ["docker", "compose", "--env-file", str(path),
                       "-f", str(ROOT / "docker-compose/docker-compose.infra.yml"),
                       "-f", str(ROOT / "docker-compose/docker-compose.services.yml"),
                       "config", "--format", "json"]
            response = subprocess.run(command, env=local.process_env({**fixture(), "FRONTEND_BASE_URL": "http://localhost:3000"}),
                                      capture_output=True, text=True, check=True)
        services = json.loads(response.stdout)["services"]
        self.assertEqual("pk_test_fixture", services["frontend"]["build"]["args"]["VITE_STRIPE_PUBLISHABLE_KEY"])
        self.assertEqual("http://localhost:3000", services["payment-service"]["environment"]["FRONTEND_BASE_URL"])
        self.assertEqual("a" * 64, services["payment-service"]["environment"]["PAYMENTS_TEST_ACTIVATION_CODE"])

    def test_isolated_network_database_redirects_webhooks_and_build_arguments(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / ".env.stripe-test"
            path.write_text("\n".join(key + "=" + value for key, value in fixture().items()))
            with patch.object(local, "ENV_FILE", path):
                response = local.compose(fixture(), "--profile", "webhooks", "config", "--format", "json", capture=True)
        config = json.loads(response.stdout)
        self.assertEqual("gratilo-stripe-test", config["name"])
        services = config["services"]
        for name in ("user-service", "payment-service"):
            service = services[name]
            env = service["environment"]
            self.assertEqual("docker", env["MICRONAUT_ENVIRONMENTS"])
            self.assertEqual("jdbc:postgresql://postgres:5432/gratilodb", env["DATASOURCES_DEFAULT_URL"])
            self.assertEqual("false", service["build"]["args"]["INCLUDE_RDS_CA"])
            self.assertNotIn("AWS_PROFILE", env)
            self.assertNotIn("ports", service)
        payment = services["payment-service"]["environment"]
        for key in ("PAYMENTS_ENABLED", "STRIPE_TEST_ONLY", "STRIPE_SANDBOX_PAYOUTS"):
            self.assertEqual("true", payment[key])
        self.assertEqual("http://localhost:3001", payment["FRONTEND_BASE_URL"])
        self.assertEqual("http://localhost:3001/pay/success?session_id={CHECKOUT_SESSION_ID}", payment["STRIPE_SUCCESS_URL"])
        self.assertEqual("http://localhost:3001/pay/cancelled?session_id={CHECKOUT_SESSION_ID}", payment["STRIPE_CANCEL_URL"])
        args = services["frontend"]["build"]["args"]
        self.assertEqual("true", args["VITE_PAYMENTS_ENABLED"])
        self.assertEqual("pk_test_fixture", args["VITE_STRIPE_PUBLISHABLE_KEY"])
        self.assertNotIn("STRIPE_SECRET_KEY", args)
        self.assertEqual("127.0.0.1", services["frontend"]["ports"][0]["host_ip"])
        self.assertNotIn("ports", services["postgres"])
        command = services["stripe-cli"]["command"]
        self.assertEqual(local.EVENTS, command[command.index("--events") + 1])
        self.assertIn("checkout.session.completed", command[command.index("--events") + 1].split(","))
        self.assertIn("payment_intent.payment_failed", command[command.index("--events") + 1].split(","))
        for flag in ("--forward-to", "--forward-connect-to"):
            self.assertEqual("http://payment-service:8082/payment-service/webhooks/stripe", command[command.index(flag) + 1])
        self.assertNotIn("--live", command)
        self.assertNotIn("--skip-verify", command)
        self.assertNotIn("external", config["networks"]["default"])
        self.assertEqual("gratilo-stripe-test_stripe-postgres", config["volumes"]["stripe-postgres"]["name"])


if __name__ == "__main__":
    unittest.main()
