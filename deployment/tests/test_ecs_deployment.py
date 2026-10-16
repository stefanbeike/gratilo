import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("renderer", ROOT / "deployment/scripts/render_task_definition.py")
renderer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(renderer)


class EcsDeploymentTest(unittest.TestCase):
    def setUp(self):
        text = (ROOT / "deployment/ecs/gratilo-task-definition.example.json").read_text()
        replacements = {"<AWS_ACCOUNT_ID>": "123456789012", "<ECS_TASK_FAMILY>": "gratilo-test",
                        "<ECS_TASK_EXECUTION_ROLE_NAME>": "test-execution", "<ECS_TASK_ROLE_NAME>": "test-task",
                        "<RDS_ENDPOINT>": "database.example.rds.amazonaws.com"}
        for old, new in replacements.items():
            text = text.replace(old, new)
        self.task = json.loads(text)
        self.config = {"AWS_ACCOUNT_ID": "123456789012", "AWS_REGION": "eu-central-1",
                       "ECS_TASK_FAMILY": self.task["family"], "ECS_TASK_EXECUTION_ROLE_ARN": self.task["executionRoleArn"],
                       "ECS_TASK_ROLE_ARN": self.task["taskRoleArn"], "ECS_CLUSTER": "test-cluster",
                       "ECS_SERVICE": "test-service", "ECS_DESIRED_COUNT": "1",
                       "IMAGE_TAG": "sha-" + "a" * 40 + "-123-1"}
        self.images = {name: f'123456789012.dkr.ecr.eu-central-1.amazonaws.com/gratilo/{name}@sha256:' + str(i) * 64
                       for i, name in enumerate(renderer.CONTAINERS, 1)}
        self.old = "arn:aws:ecs:eu-central-1:123456789012:task-definition/gratilo-test:1"
        self.new = self.old[:-1] + "2"
        self.task.update(taskDefinitionArn=self.old, revision=1, status="ACTIVE", registeredBy="metadata")
        self.response = {"taskDefinition": self.task, "tags": [{"key": "product", "value": "gratilo"}]}
        self.service = {"services": [{"status": "ACTIVE", "taskDefinition": self.old,
            "platformVersion": "1.4.0", "deploymentController": {"type": "ECS"},
            "deploymentConfiguration": {"deploymentCircuitBreaker": {"enable": True, "rollback": True}},
            "loadBalancers": [{"containerName": "frontend", "containerPort": 80}],
            "desiredCount": 1, "runningCount": 1,
            "deployments": [{"status": "PRIMARY", "taskDefinition": self.old, "rolloutState": "COMPLETED"}]}], "failures": []}

    def test_replaces_all_images_and_preserves_every_other_setting(self):
        original = copy.deepcopy(self.response)
        actual = renderer.render(self.response, self.images, self.config)
        expected = copy.deepcopy(self.task)
        for key in renderer.READ_ONLY:
            expected.pop(key, None)
        for container in expected["containerDefinitions"]:
            container["image"] = self.images[container["name"]]
        expected["tags"] = original["tags"]
        self.assertEqual(expected, actual)
        self.assertEqual(original, self.response)

    def test_rejects_missing_container_and_foreign_role(self):
        for mutate in [lambda t: t["containerDefinitions"].pop(),
                       lambda t: t.update(executionRoleArn="arn:aws:iam::123456789012:role/other")]:
            task = copy.deepcopy(self.response)
            mutate(task["taskDefinition"])
            with self.assertRaises(ValueError):
                renderer.render(task, self.images, self.config)

    def test_rejects_tag_reference_or_foreign_repository(self):
        for image in ["example:latest", self.images["frontend"].replace("123456789012", "999999999999")]:
            with self.assertRaises(ValueError):
                renderer.render(self.response, {**self.images, "frontend": image}, self.config)

    def test_rejects_plaintext_secret_wrong_domain_missing_health_and_local_database(self):
        for key, value in [("JWT_SECRET", "not-a-real-secret"), ("FRONTEND_BASE_URL", "https://gratilo.com"),
                           ("DATASOURCES_DEFAULT_URL", "jdbc:postgresql://localhost:5432/gratilodb"),
                           ("FEATURE_TIP_POOLS_ENABLED", "true")]:
            task = copy.deepcopy(self.response)
            env = task["taskDefinition"]["containerDefinitions"][1]["environment"]
            env[:] = [entry for entry in env if entry["name"] != key]
            env.append({"name": key, "value": value})
            with self.assertRaises(ValueError):
                renderer.render(task, self.images, self.config)
        task = copy.deepcopy(self.response)
        task["taskDefinition"]["containerDefinitions"][0].pop("healthCheck")
        with self.assertRaises(ValueError):
            renderer.render(task, self.images, self.config)

    def test_service_requires_circuit_breaker_and_frontend_target(self):
        self.assertEqual(self.old, renderer.validate_service(self.service, self.config))
        for key in ["deploymentConfiguration", "loadBalancers"]:
            service = copy.deepcopy(self.service)
            service["services"][0].pop(key)
            with self.assertRaises(ValueError):
                renderer.validate_service(service, self.config)

    def run_mock_deployment(self, mode):
        with tempfile.TemporaryDirectory(prefix="gratilo-deploy-test-") as folder:
            root = Path(folder)
            (root / "task.json").write_text(json.dumps(self.response))
            (root / "service.json").write_text(json.dumps(self.service))
            mock = root / "aws"
            mock.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
p = pathlib.Path(os.environ['MOCK_DIR']); args = sys.argv[1:]; operation = args[1]
with (p/'calls').open('a') as out: out.write(operation+'\\n')
if operation == 'describe-services':
    data = json.loads((p/'service.json').read_text())
    if (p/'updated').exists() and os.environ['MOCK_MODE'] != 'rollback':
        data['services'][0]['taskDefinition'] = os.environ['NEW_TASK']
        data['services'][0]['deployments'][0]['taskDefinition'] = os.environ['NEW_TASK']
    print(json.dumps(data))
elif operation == 'describe-task-definition': print((p/'task.json').read_text())
elif operation == 'describe-images':
    service = args[args.index('--repository-name')+1].split('/')[1]
    print('sha256:'+str(['frontend','user-service','payment-service'].index(service)+1)*64)
elif operation == 'register-task-definition': print(os.environ['NEW_TASK'])
elif operation == 'update-service': (p/'updated').touch(); print('test-service-arn')
elif operation == 'wait': sys.exit(42 if os.environ['MOCK_MODE']=='wait-failure' else 0)
elif operation == 'list-tasks': print('None')
else: sys.exit('Unexpected AWS operation: '+operation)
''')
            mock.chmod(0o755)
            environment = {**self.config, "PATH": folder + os.pathsep + "/usr/bin:/bin", "MOCK_DIR": folder,
                           "NEW_TASK": self.new, "MOCK_MODE": mode, "GITHUB_STEP_SUMMARY": str(root / "summary")}
            environment.update({name.upper().replace("-", "_") + "_IMAGE": value for name, value in self.images.items()})
            result = subprocess.run(["bash", "deployment/scripts/deploy_ecs.sh"], cwd=ROOT, env=environment,
                                    text=True, capture_output=True, timeout=20)
            return result, (root / "calls").read_text()

    def test_full_deployment_with_mocked_aws(self):
        result, calls = self.run_mock_deployment("success")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, calls.splitlines().count("register-task-definition"))
        self.assertEqual(1, calls.splitlines().count("update-service"))

    def test_stable_rollback_is_a_failure(self):
        result, calls = self.run_mock_deployment("rollback")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("stable rollback", result.stderr)
        self.assertIn("list-tasks", calls)

    def test_wait_failure_remains_failure_and_collects_diagnostics(self):
        result, calls = self.run_mock_deployment("wait-failure")
        self.assertEqual(42, result.returncode)
        self.assertIn("list-tasks", calls)


if __name__ == "__main__":
    unittest.main()
