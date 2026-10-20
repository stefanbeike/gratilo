import contextlib
import io
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import verify_ecs_health as health


class HealthVerificationTest(unittest.TestCase):
    def setUp(self):
        self.service = {"status": "ACTIVE", "desiredCount": 1, "runningCount": 1,
                        "taskDefinition": "revision", "pendingCount": 0,
                        "deployments": [{"taskDefinition": "revision", "rolloutState": "COMPLETED"}],
                        "loadBalancers": [{"containerName": "frontend", "containerPort": 80, "targetGroupArn": "target"}]}
        self.tasks = {"tasks": [{"taskDefinitionArn": "revision", "lastStatus": "RUNNING",
                     "healthStatus": "HEALTHY", "containers": [{"name": name, "lastStatus": "RUNNING", "healthStatus": "HEALTHY"}
                                                                  for name in health.CONTAINERS]}]}
        self.targets = {"TargetHealthDescriptions": [{"TargetHealth": {"State": "healthy"}}]}
        self.env = {"ECS_CLUSTER": "cluster", "ECS_SERVICE": "service"}
        self.definition = {"taskDefinition": {"taskDefinitionArn": "revision", "containerDefinitions": [
            {"name": name, "environment": [{"name": "FEATURE_TIP_POOLS_ENABLED", "value": "false"}]}
            for name in ("user-service", "payment-service")]}}

    def responses(self, task_arns=None):
        return [{"services": [self.service]}, {"taskArns": ["task"] if task_arns is None else task_arns},
                *([self.tasks] if task_arns != [] else []), self.targets, self.definition]

    def test_iam_errors_name_the_exact_permission_without_echoing_response(self):
        for operation, action in health.ACTIONS.items():
            result = Mock(returncode=254, stdout="sensitive-response", stderr="AccessDeniedException sensitive-credential")
            with self.subTest(action=action), patch.object(health.subprocess, "run", return_value=result):
                with self.assertRaises(health.HealthError) as caught:
                    health.aws(*operation)
                self.assertEqual("IAM_ACCESS_DENIED", caught.exception.category)
                self.assertIn(action, str(caught.exception))
                self.assertNotIn("sensitive", str(caught.exception))

    def test_authentication_api_and_json_errors_are_safe_and_distinct(self):
        for code, stderr, stdout, category in [
            (254, "ExpiredToken secret-marker", "", "AWS_AUTHENTICATION"),
            (254, "ThrottlingException secret-marker", "", "AWS_API_ERROR"),
            (0, "", "secret-marker", "AWS_API_ERROR"),
            (0, "", "[]", "AWS_API_ERROR"),
        ]:
            with self.subTest(category=category), patch.object(health.subprocess, "run", return_value=Mock(returncode=code, stderr=stderr, stdout=stdout)):
                with self.assertRaises(health.HealthError) as caught:
                    health.aws("ecs", "list-tasks")
                self.assertEqual(category, caught.exception.category)
                self.assertNotIn("secret-marker", str(caught.exception))

    def test_preflight_checks_all_four_apis_but_not_existing_health_or_https(self):
        self.tasks["tasks"][0]["containers"][0]["healthStatus"] = "UNHEALTHY"
        self.targets["TargetHealthDescriptions"][0]["TargetHealth"]["State"] = "unhealthy"
        with patch.dict(os.environ, self.env), patch.object(health, "aws", side_effect=self.responses()) as aws, \
                patch.object(health, "curl") as curl, contextlib.redirect_stdout(io.StringIO()):
            health.verify("revision", permissions_only=True)
        self.assertEqual(list(health.ACTIONS)[:4], [call.args[:2] for call in aws.call_args_list])
        curl.assert_not_called()

    def test_empty_baseline_defers_only_describe_tasks_without_creating_a_fake_task(self):
        output = io.StringIO()
        with patch.dict(os.environ, self.env), patch.object(health, "aws", side_effect=self.responses([])) as aws, \
                patch.object(health, "curl") as curl, contextlib.redirect_stdout(output):
            health.verify("revision", permissions_only=True)
        self.assertEqual(3, aws.call_count)
        self.assertIn("no running tasks", output.getvalue())
        self.assertIn("DescribeTasks", output.getvalue())
        curl.assert_not_called()

    def test_service_must_be_the_selected_completed_revision_with_nonzero_count(self):
        health.validate_service(self.service, "revision")
        for changes in ({"desiredCount": 0}, {"runningCount": 0}, {"pendingCount": 1},
                        {"taskDefinition": "old"}, {"deployments": []}, {"status": "DRAINING"},
                        {"deployments": [{"taskDefinition": "revision", "rolloutState": "IN_PROGRESS"}]},
                        {"deployments": [self.service["deployments"][0]] * 2}):
            with self.subTest(changes=changes), self.assertRaises(health.HealthError) as caught:
                health.validate_service({**self.service, **changes}, "revision")
            self.assertEqual("ECS_SERVICE_STATE", caught.exception.category)

    def test_container_and_alb_failures_are_distinct(self):
        self.tasks["tasks"][0]["containers"][1]["healthStatus"] = "UNHEALTHY"
        with self.assertRaises(health.HealthError) as caught:
            health.validate_tasks(self.tasks, "revision", 1)
        self.assertEqual("CONTAINER_UNHEALTHY", caught.exception.category)
        self.assertIn("user-service", str(caught.exception))
        with self.assertRaises(health.HealthError) as caught:
            health.validate_targets({"TargetHealthDescriptions": []})
        self.assertEqual("ALB_UNHEALTHY", caught.exception.category)

    def test_tls_network_and_invalid_http_results_do_not_log_data(self):
        for code, body, category in [(60, "", "HTTPS_TLS"), (7, "", "HTTPS_NETWORK"), (0, "secret-marker", "HTTPS_STATUS")]:
            with self.subTest(category=category), patch.object(health.subprocess, "run", return_value=Mock(returncode=code, stdout=body, stderr="secret-marker")):
                with self.assertRaises(health.HealthError) as caught:
                    health.curl("/")
                self.assertEqual(category, caught.exception.category)
                self.assertNotIn("secret-marker", str(caught.exception))

    def test_https_requests_enforce_tls_and_do_not_follow_redirects(self):
        with patch.object(health.subprocess, "run", return_value=Mock(returncode=0, stdout="body\n200")) as run:
            self.assertEqual(("body", "200"), health.curl("/"))
        args = run.call_args.args[0]
        self.assertIn("=https", args)
        self.assertEqual("https://gratilo.eu/", args[-1])
        for unsafe in ("-k", "--insecure", "-L", "--location"):
            self.assertNotIn(unsafe, args)

    def test_redirect_bad_features_and_public_payment_route_fail_closed(self):
        for responses, category in [
            ([("redirect-body", "301")], "HTTPS_STATUS"),
            ([("html", "200"), ("secret-marker", "200")], "BACKEND_RESPONSE"),
            ([("html", "200"), ('{"tipPoolsEnabled":true}', "200")], "BACKEND_RESPONSE"),
            ([("html", "200"), ('{"tipPoolsEnabled":false}', "200"), ("secret-marker", "200")], "HTTPS_STATUS"),
        ]:
            with self.subTest(category=category), patch.dict(os.environ, self.env), \
                    patch.object(health, "aws", side_effect=self.responses()), patch.object(health, "curl", side_effect=responses):
                with self.assertRaises(health.HealthError) as caught:
                    health.verify("revision")
                self.assertEqual(category, caught.exception.category)
                self.assertNotIn("secret-marker", str(caught.exception))

    def test_success_checks_only_safe_get_endpoints(self):
        with patch.dict(os.environ, self.env), patch.object(health, "aws", side_effect=self.responses()), \
                patch.object(health, "curl", side_effect=[("html", "200"), ('{"tipPoolsEnabled":false}', "200"), ("", "401")]) as curl, \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, health.main(["revision"]))
        self.assertEqual(["/", "/user-service/features", "/payment-service/transactions"], [call.args[0] for call in curl.call_args_list])

    def test_stripe_test_features_follow_selected_revision_even_during_rollback(self):
        for enabled in (True, False):
            for container in self.definition["taskDefinition"]["containerDefinitions"]:
                container["environment"][0]["value"] = "true" if enabled else "false"
            # Workflow mode may differ from the revision being verified after rollback.
            with self.subTest(enabled=enabled), patch.dict(os.environ, {**self.env, "STRIPE_TEST_MODE": str(not enabled).lower()}), \
                    patch.object(health, "aws", side_effect=self.responses()), \
                    patch.object(health, "curl", side_effect=[("html", "200"), (json.dumps({"tipPoolsEnabled": enabled}), "200"), ("", "401")]), \
                    contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, health.main(["revision"]))

    def test_feature_response_requires_exact_boolean_and_expected_value(self):
        for value in (True, 0, "false", None):
            with self.subTest(value=value), patch.dict(os.environ, self.env), \
                    patch.object(health, "aws", side_effect=self.responses()), \
                    patch.object(health, "curl", side_effect=[("html", "200"), (json.dumps({"tipPoolsEnabled": value}), "200")]), \
                    self.assertRaises(health.HealthError) as caught:
                health.verify("revision")
            self.assertEqual("BACKEND_RESPONSE", caught.exception.category)

    def test_task_feature_expectations_reject_wrong_revision_and_inconsistent_flags(self):
        with self.assertRaises(health.HealthError):
            health.expected_features(self.definition, "different-revision")
        for value in ("true", "invalid"):
            self.definition["taskDefinition"]["containerDefinitions"][0]["environment"][0]["value"] = value
            with self.subTest(value=value), self.assertRaises(health.HealthError):
                health.expected_features(self.definition, "revision")

    def test_legacy_task_without_pool_flags_uses_disabled_application_default(self):
        for container in self.definition["taskDefinition"]["containerDefinitions"]:
            container["environment"] = []
        self.assertEqual({"tipPoolsEnabled": False}, health.expected_features(self.definition, "revision"))

    def test_main_names_access_denied_and_suppresses_unexpected_exception(self):
        for error, category in [(health.HealthError("IAM_ACCESS_DENIED", "ecs:ListTasks denied"), "IAM_ACCESS_DENIED"),
                                (KeyError("secret-marker"), "HEALTH_CONFIGURATION")]:
            stderr = io.StringIO()
            with patch.object(health, "verify", side_effect=error), contextlib.redirect_stderr(stderr):
                self.assertEqual(1, health.main(["revision"]))
            self.assertIn(category, stderr.getvalue())
            self.assertNotIn("secret-marker", stderr.getvalue())


class HealthIamPolicyTest(unittest.TestCase):
    def setUp(self):
        path = Path(__file__).resolve().parents[2] / "deployment/iam/github-ecs-deployment-policy.json"
        self.statements = {s["Sid"]: s for s in json.loads(path.read_text())["Statement"]}

    def test_list_tasks_uses_container_instance_resource_and_exact_cluster_condition(self):
        statement = self.statements["ListOnlyClusterTasks"]
        self.assertEqual("ecs:ListTasks", statement["Action"])
        self.assertEqual("arn:aws:ecs:eu-central-1:070351676632:container-instance/gratilo-prd-cluster/*", statement["Resource"])
        self.assertEqual({"ArnEquals": {"ecs:cluster": "arn:aws:ecs:eu-central-1:070351676632:cluster/gratilo-prd-cluster"}}, statement["Condition"])

    def test_other_read_permissions_use_supported_resources_without_wildcard_actions(self):
        self.assertEqual("arn:aws:ecs:eu-central-1:070351676632:task/gratilo-prd-cluster/*", self.statements["ReadOnlyClusterTasks"]["Resource"])
        self.assertEqual("*", self.statements["ReadTargetHealth"]["Resource"])
        self.assertEqual("ecs:DescribeTaskDefinition", self.statements["ReadTaskDefinitionMetadata"]["Action"])
        self.assertEqual("*", self.statements["ReadTaskDefinitionMetadata"]["Resource"])
        for statement in self.statements.values():
            actions = statement["Action"] if isinstance(statement["Action"], list) else [statement["Action"]]
            self.assertTrue(all("*" not in action for action in actions))


if __name__ == "__main__":
    unittest.main()
