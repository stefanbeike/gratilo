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
        replacements = {"gratilo-prd-app": "gratilo-test", "070351676632": "123456789012",
                        "<STRIPE_TEST_SECRET_ARN>": "arn:aws:secretsmanager:eu-central-1:123456789012:secret:gratilo/test/stripe-fixture",
                        "<AWS_ACCOUNT_ID>": "123456789012", "<ECS_TASK_FAMILY>": "gratilo-test",
                        "<ECS_TASK_EXECUTION_ROLE_NAME>": "test-execution", "<ECS_TASK_ROLE_NAME>": "test-task",
                        "<RDS_ENDPOINT>": "database.example.rds.amazonaws.com"}
        for old, new in replacements.items():
            text = text.replace(old, new)
        self.task = json.loads(text)
        self.config = {"AWS_ACCOUNT_ID": "123456789012", "AWS_REGION": "eu-central-1",
                       "ECS_TASK_FAMILY": self.task["family"], "ECS_TASK_EXECUTION_ROLE_ARN": self.task["executionRoleArn"],
                       "ECS_TASK_ROLE_ARN": self.task["taskRoleArn"], "ECS_CLUSTER": "test-cluster",
                       "ECS_SERVICE": "test-service", "ECS_DESIRED_COUNT": "1",
                       "STRIPE_TEST_MODE": "false",
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
            "loadBalancers": [{"containerName": "frontend", "containerPort": 80, "targetGroupArn": "test-target"}],
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
            if container["name"]=="payment-service":
                container["environment"].extend([{"name":"STRIPE_MANUAL_PAYOUTS_API_APPROVED","value":"false"},{"name":"STRIPE_MANUAL_PAYOUT_SETUP_ENABLED","value":"false"},{"name":"STRIPE_MANUAL_PAYOUT_APPROVED_ACCOUNTS","value":""},{"name":"STRIPE_CONNECT_STRIPE_PRICING_ENABLED","value":"false"},{"name":"STRIPE_CONNECT_PLATFORM_FEES_ENABLED","value":"false"}])
        # ECS treats environment variables as a map; renderer insertion order is not part of the task contract.
        for task in (expected, actual):
            for container in task["containerDefinitions"]:
                container["environment"].sort(key=lambda entry: entry["name"])
        expected["tags"] = original["tags"]
        self.assertEqual(expected, actual)
        self.assertEqual(original, self.response)

    def test_repairs_inherited_stripe_redirects_to_fixed_gratilo_urls(self):
        response = copy.deepcopy(self.response)
        payment = response["taskDefinition"]["containerDefinitions"][2]
        for entry in payment["environment"]:
            if entry["name"] == "STRIPE_SUCCESS_URL":
                entry["value"] = "https://old.example/success"
            elif entry["name"] == "STRIPE_CANCEL_URL":
                entry["value"] = "https://old.example/cancel"
        rendered = renderer.render(response, self.images, self.config)
        env = {entry["name"]: entry["value"] for entry in rendered["containerDefinitions"][2]["environment"]}
        self.assertEqual("https://gratilo.eu/pay/success?session_id={CHECKOUT_SESSION_ID}", env["STRIPE_SUCCESS_URL"])
        self.assertEqual("https://gratilo.eu/pay/cancelled?session_id={CHECKOUT_SESSION_ID}", env["STRIPE_CANCEL_URL"])
        self.assertEqual("https://old.example/cancel", next(entry["value"] for entry in payment["environment"] if entry["name"] == "STRIPE_CANCEL_URL"))

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
                           ("DATASOURCES_DEFAULT_URL", "jdbc:postgresql://localhost:5432/gratilodb")]:
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

    def test_rejects_inconsistent_shared_secrets(self):
        for key in ("JWT_SECRET", "INTERNAL_API_KEY"):
            response = copy.deepcopy(self.response)
            for secret in response["taskDefinition"]["containerDefinitions"][2]["secrets"]:
                if secret["name"] == key:
                    secret["valueFrom"] = secret["valueFrom"].replace("gratilo/prd/", "gratilo/different/")
            with self.assertRaisesRegex(ValueError, "same secret reference"):
                renderer.render(response, self.images, self.config)

    def test_allows_only_explicit_restricted_stripe_test_configuration(self):
        response = copy.deepcopy(self.response)
        payment = response["taskDefinition"]["containerDefinitions"][2]
        values = {"PAYMENTS_ENABLED": "true", "STRIPE_TEST_ONLY": "true", "STRIPE_SANDBOX_PAYOUTS": "true",
                  "STRIPE_DEMO_ENABLED": "false", "PAYMENTS_TEST_USER_IDS": "", "FEATURE_TIP_POOLS_ENABLED": "true"}
        env = payment["environment"]
        for key, value in values.items():
            env[:] = [entry for entry in env if entry["name"] != key]
            env.append({"name": key, "value": value})
        root = "arn:aws:secretsmanager:eu-central-1:123456789012:secret:gratilo/prd/stripe-test-fixture"
        for service_name in ("user-service",):
            service_container = next(c for c in response["taskDefinition"]["containerDefinitions"] if c["name"] == service_name)
            service_container["environment"] = [e for e in service_container["environment"] if e["name"] != "FEATURE_TIP_POOLS_ENABLED"] + [{"name": "FEATURE_TIP_POOLS_ENABLED", "value": "true"}]
        payment["secrets"].extend([
            {"name": "STRIPE_SECRET_KEY", "valueFrom": root + ":secret_key::"},
            {"name": "STRIPE_WEBHOOK_SECRET", "valueFrom": root + ":webhook_secret::"},
            {"name": "PAYMENTS_TEST_ACTIVATION_CODE", "valueFrom": root + ":activation_code::"},
        ])
        self.config["STRIPE_TEST_MODE"] = "true"
        rendered = renderer.render(response, self.images, self.config)
        rendered_payment = next(c for c in rendered["containerDefinitions"] if c["name"] == "payment-service")
        rendered_env = {entry["name"]: entry["value"] for entry in rendered_payment["environment"]}
        self.assertEqual("true", rendered_env["PAYMENTS_ENABLED"])
        self.assertEqual("true", rendered_env["STRIPE_SANDBOX_PAYOUTS"])
        self.assertEqual("true", rendered_env["FEATURE_TIP_POOLS_ENABLED"])
        rendered_user = next(c for c in rendered["containerDefinitions"] if c["name"] == "user-service")
        self.assertEqual("true", {entry["name"]: entry["value"] for entry in rendered_user["environment"]}["FEATURE_TIP_POOLS_ENABLED"])

    def test_direct_pricing_requires_explicit_sandbox_release_and_same_secret(self):
        with self.assertRaisesRegex(ValueError,"only be enabled in Stripe test mode"):
            renderer.render(self.response,self.images,{**self.config,"GRATILO_NEW_PRICING_ENABLED":"true"})
        response=copy.deepcopy(self.response)
        payment=response["taskDefinition"]["containerDefinitions"][2]
        root="arn:aws:secretsmanager:eu-central-1:123456789012:secret:gratilo/prd/stripe-test-fixture"
        payment["secrets"].extend([{"name":name,"valueFrom":root+":"+field+"::"} for name,field in [
            ("STRIPE_SECRET_KEY","secret_key"),("STRIPE_WEBHOOK_SECRET","webhook_secret"),("PAYMENTS_TEST_ACTIVATION_CODE","activation_code")]])
        payment["environment"].append({"name":"GRATILO_POOL_COMMISSION_FIXED_MINOR","value":"45"})
        config={**self.config,"STRIPE_TEST_MODE":"true","GRATILO_NEW_PRICING_ENABLED":"true"}
        rendered=renderer.render(response,self.images,config)
        result=rendered["containerDefinitions"][2]
        env={e["name"]:e["value"] for e in result["environment"]}
        refs={e["name"]:e["valueFrom"] for e in result["secrets"]}
        self.assertEqual("true",env["GRATILO_NEW_PRICING_ENABLED"])
        self.assertEqual("5.00",env["GRATILO_PLATFORM_FEE_PERCENT"])
        self.assertEqual("45",env["GRATILO_PLATFORM_FEE_FIXED_MINOR"])
        self.assertNotIn("GRATILO_POOL_COMMISSION_FIXED_MINOR",env)
        self.assertEqual("200",env["GRATILO_POOL_TIP_MIN_MINOR"])
        self.assertEqual("50000",env["GRATILO_POOL_TIP_MAX_MINOR"])
        self.assertEqual(root+":direct_webhook_secret::",refs["STRIPE_DIRECT_WEBHOOK_SECRET"])
        self.assertNotIn("STRIPE_DIRECT_WEBHOOK_SECRET",env)
        payment["secrets"].append({"name":"STRIPE_DIRECT_WEBHOOK_SECRET","valueFrom":root+":webhook_secret::"})
        with self.assertRaisesRegex(ValueError,"Direct webhook"):
            renderer.render(response,self.images,config)

    def test_external_sandbox_payouts_require_explicit_confirmation_and_never_inherit_account_filters(self):
        response=copy.deepcopy(self.response)
        payment=response["taskDefinition"]["containerDefinitions"][2]
        root="arn:aws:secretsmanager:eu-central-1:123456789012:secret:gratilo/prd/stripe-test-fixture"
        payment["secrets"].extend([{"name":name,"valueFrom":root+":"+field+"::"} for name,field in [
            ("STRIPE_SECRET_KEY","secret_key"),("STRIPE_WEBHOOK_SECRET","webhook_secret"),("PAYMENTS_TEST_ACTIVATION_CODE","activation_code")]])
        payment["environment"].append({"name":"STRIPE_MANUAL_PAYOUT_APPROVED_ACCOUNTS","value":"acct_old"})
        config={**self.config,"STRIPE_TEST_MODE":"true","STRIPE_TEST_PAYOUTS_ENABLED":"true"}
        with self.assertRaisesRegex(ValueError,"separate confirmation"):
            renderer.render(response,self.images,config)
        config["STRIPE_TEST_PAYOUT_CONFIRMATION"]="ENABLE STRIPE TEST PAYOUTS"
        rendered=renderer.render(response,self.images,config)
        payment=rendered["containerDefinitions"][2]
        env={v["name"]:v["value"] for v in payment["environment"]}
        self.assertEqual("false",env["STRIPE_SANDBOX_PAYOUTS"])
        self.assertEqual("true",env["STRIPE_MANUAL_PAYOUTS_API_APPROVED"])
        self.assertEqual("true",env["STRIPE_MANUAL_PAYOUT_SETUP_ENABLED"])
        self.assertEqual("",env["STRIPE_MANUAL_PAYOUT_APPROVED_ACCOUNTS"])
        self.assertEqual(root+":direct_webhook_secret::",next(v["valueFrom"] for v in payment["secrets"] if v["name"]=="STRIPE_DIRECT_WEBHOOK_SECRET"))
        with self.assertRaisesRegex(ValueError,"require Stripe test mode"):
            renderer.render(response,self.images,{**config,"STRIPE_TEST_MODE":"false"})

    def test_new_pricing_is_disabled_on_a_following_release_unless_explicitly_requested(self):
        response=copy.deepcopy(self.response)
        payment=response["taskDefinition"]["containerDefinitions"][2]
        payment["environment"].append({"name":"GRATILO_NEW_PRICING_ENABLED","value":"true"})
        actual=renderer.render(response,self.images,self.config)
        env={e["name"]:e["value"] for e in actual["containerDefinitions"][2]["environment"]}
        self.assertEqual("false",env["GRATILO_NEW_PRICING_ENABLED"])

    def test_rejects_live_or_unrestricted_payment_configurations(self):
        base = copy.deepcopy(self.response)
        payment = base["taskDefinition"]["containerDefinitions"][2]
        env = payment["environment"]
        values = {"PAYMENTS_ENABLED": "true", "STRIPE_TEST_ONLY": "true", "STRIPE_SANDBOX_PAYOUTS": "true",
                  "STRIPE_DEMO_ENABLED": "false", "PAYMENTS_TEST_USER_IDS": "", "FEATURE_TIP_POOLS_ENABLED": "false"}
        for key, value in values.items():
            env[:] = [entry for entry in env if entry["name"] != key]
            env.append({"name": key, "value": value})
        root = "arn:aws:secretsmanager:eu-central-1:123456789012:secret:gratilo/prd/stripe-test-fixture"
        payment["secrets"].extend([
            {"name": "STRIPE_SECRET_KEY", "valueFrom": root + ":secret_key::"},
            {"name": "STRIPE_WEBHOOK_SECRET", "valueFrom": root + ":webhook_secret::"},
            {"name": "PAYMENTS_TEST_ACTIVATION_CODE", "valueFrom": root + ":activation_code::"},
        ])
        mutations = [
            (None, None, "STRIPE_SECRET_KEY"),
            (None, None, "STRIPE_WEBHOOK_SECRET"),
            (None, None, "PAYMENTS_TEST_ACTIVATION_CODE"),
        ]
        for env_name, value, secret_name in mutations:
            with self.subTest(env=env_name, secret=secret_name):
                response = copy.deepcopy(base)
                payment = response["taskDefinition"]["containerDefinitions"][2]
                if env_name:
                    item = next(entry for entry in payment["environment"] if entry["name"] == env_name)
                    item["value"] = value
                else:
                    payment["secrets"] = [entry for entry in payment["secrets"] if entry["name"] != secret_name]
                with self.assertRaises(ValueError):
                    renderer.render(response, self.images, {**self.config, "STRIPE_TEST_MODE": "true"})
        for bad_ref in (root.replace("stripe-test", "stripe-live") + ":secret_key::",
                        root + ":live_secret_key::"):
            response = copy.deepcopy(base)
            payment = response["taskDefinition"]["containerDefinitions"][2]
            next(entry for entry in payment["secrets"] if entry["name"] == "STRIPE_SECRET_KEY")["valueFrom"] = bad_ref
            with self.assertRaises(ValueError):
                renderer.render(response, self.images, {**self.config, "STRIPE_TEST_MODE": "true"})
        original = copy.deepcopy(base)
        for source_enabled in (False, True):
            for target_enabled in (False, True):
                with self.subTest(source=source_enabled, target=target_enabled):
                    source = copy.deepcopy(base)
                    for container in source["taskDefinition"]["containerDefinitions"][1:]:
                        for entry in container["environment"]:
                            if entry["name"] in ("PAYMENTS_ENABLED", "STRIPE_SANDBOX_PAYOUTS", "FEATURE_TIP_POOLS_ENABLED"):
                                entry["value"] = str(source_enabled).lower()
                    # Inherited unsafe/stale settings must be replaced in the candidate.
                    payment = source["taskDefinition"]["containerDefinitions"][2]
                    for entry in payment["environment"]:
                        if entry["name"] == "STRIPE_DEMO_ENABLED": entry["value"] = "true"
                        if entry["name"] == "STRIPE_TEST_ONLY": entry["value"] = "false"
                        if entry["name"] == "PAYMENTS_TEST_USER_IDS": entry["value"] = "old-test-user"
                    before = copy.deepcopy(source)
                    candidate = renderer.render(source, self.images, {**self.config, "STRIPE_TEST_MODE": str(target_enabled).lower()})
                    for container in candidate["containerDefinitions"][1:]:
                        env = {e["name"]: e["value"] for e in container["environment"]}
                        self.assertEqual(str(target_enabled).lower(), env["FEATURE_TIP_POOLS_ENABLED"])
                        if container["name"] == "payment-service":
                            self.assertEqual(str(target_enabled).lower(), env["PAYMENTS_ENABLED"])
                            self.assertEqual(str(target_enabled).lower(), env["STRIPE_SANDBOX_PAYOUTS"])
                            self.assertEqual("true", env["STRIPE_TEST_ONLY"])
                            self.assertEqual("false", env["STRIPE_DEMO_ENABLED"])
                            self.assertEqual("", env["PAYMENTS_TEST_USER_IDS"])
                    self.assertEqual(before, source)
        self.assertEqual(original, base)

    def test_rejects_invalid_mode_and_duplicate_inherited_environment(self):
        with self.assertRaisesRegex(ValueError, "STRIPE_TEST_MODE"):
            renderer.render(self.response, self.images, {**self.config, "STRIPE_TEST_MODE": "invalid"})
        task = copy.deepcopy(self.response)
        env = task["taskDefinition"]["containerDefinitions"][1]["environment"]
        env.append(copy.deepcopy(env[0]))
        with self.assertRaisesRegex(ValueError, "Duplicate environment"):
            renderer.render(task, self.images, self.config)

    def test_service_requires_circuit_breaker_and_frontend_target(self):
        self.assertEqual(self.old, renderer.validate_service(self.service, self.config))
        for key in ["deploymentConfiguration", "loadBalancers"]:
            service = copy.deepcopy(self.service)
            service["services"][0].pop(key)
            with self.assertRaises(ValueError):
                renderer.validate_service(service, self.config)

    def run_mock_deployment(self, mode, stripe_test=False, test_confirmation="ENABLE STRIPE TEST PAYMENTS", source_stripe_test=False):
        with tempfile.TemporaryDirectory(prefix="gratilo-deploy-test-") as folder:
            root = Path(folder)
            response = copy.deepcopy(self.response)
            if stripe_test or source_stripe_test:
                payment = response["taskDefinition"]["containerDefinitions"][2]
                values = {"PAYMENTS_ENABLED": str(source_stripe_test).lower(), "STRIPE_TEST_ONLY": "true", "STRIPE_SANDBOX_PAYOUTS": str(source_stripe_test).lower(),
                  "STRIPE_DEMO_ENABLED": "false", "PAYMENTS_TEST_USER_IDS": "", "FEATURE_TIP_POOLS_ENABLED": "false"}
                for key, value in values.items():
                    payment["environment"][:] = [entry for entry in payment["environment"] if entry["name"] != key]
                    payment["environment"].append({"name": key, "value": value})
                root_arn = "arn:aws:secretsmanager:eu-central-1:123456789012:secret:gratilo/prd/stripe-test-fixture"
                payment["secrets"].extend([
                    {"name": "STRIPE_SECRET_KEY", "valueFrom": root_arn + ":secret_key::"},
                    {"name": "STRIPE_WEBHOOK_SECRET", "valueFrom": root_arn + ":webhook_secret::"},
                    {"name": "PAYMENTS_TEST_ACTIVATION_CODE", "valueFrom": root_arn + ":activation_code::"},
                ])
            (root / "task.json").write_text(json.dumps(response))
            (root / "service.json").write_text(json.dumps(self.service))
            mock = root / "aws"
            mock.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
p = pathlib.Path(os.environ['MOCK_DIR']); args = sys.argv[1:]; operation = args[1]
with (p/'calls').open('a') as out: out.write(operation+'\\n')
denied = ((os.environ['MOCK_MODE'] == 'iam-denied-preflight' and operation == 'list-tasks')
          or (os.environ['MOCK_MODE'] == 'iam-denied-after-update' and operation == 'list-tasks' and (p/'updated').exists()))
if operation == 'list-tasks':
    count = int((p/'list-count').read_text())+1 if (p/'list-count').exists() else 1
    (p/'list-count').write_text(str(count))
    denied = denied or (os.environ['MOCK_MODE'] == 'iam-denied-persistent' and count > 1)
if denied:
    print('An error occurred (AccessDeniedException) when calling the ListTasks operation', file=sys.stderr)
    sys.exit(254)
if operation == 'describe-services':
    data = json.loads((p/'service.json').read_text())
    if (p/'updated').exists() and os.environ['MOCK_MODE'] != 'rollback':
        data['services'][0]['taskDefinition'] = os.environ['NEW_TASK']
        data['services'][0]['deployments'][0]['taskDefinition'] = os.environ['NEW_TASK']
        if os.environ['MOCK_MODE'] == 'foreign-revision':
            data['services'][0]['taskDefinition'] = 'unrelated-revision'
        if os.environ['MOCK_MODE'] == 'rollout-state-lag':
            count = int((p/'rollout-polls').read_text()) + 1 if (p/'rollout-polls').exists() else 1
            (p/'rollout-polls').write_text(str(count))
            if count == 1: data['services'][0]['deployments'][0]['rolloutState'] = 'IN_PROGRESS'
    print(json.dumps(data))
elif operation == 'describe-task-definition':
    requested = args[args.index('--task-definition')+1]
    source = 'registered-task.json' if requested == os.environ['NEW_TASK'] and (p/'registered-task.json').exists() else 'task.json'
    print((p/source).read_text())
elif operation == 'describe-images':
    service = args[args.index('--repository-name')+1].split('/')[1]
    print('sha256:'+('f' if os.environ['MOCK_MODE']=='digest-conflict' else str(['frontend','user-service','payment-service'].index(service)+1))*64)
elif operation == 'register-task-definition':
    candidate = json.loads(pathlib.Path(args[args.index('--cli-input-json')+1].removeprefix('file://')).read_text())
    candidate['taskDefinitionArn'] = os.environ['NEW_TASK']
    (p/'registered-task.json').write_text(json.dumps({'taskDefinition':candidate}))
    print(os.environ['NEW_TASK'])
elif operation == 'update-service':
    if args[args.index('--task-definition')+1] == os.environ['NEW_TASK']:
        (p/'updated').touch()
    else:
        (p/'updated').unlink(missing_ok=True)
    print('test-service-arn')
elif operation == 'wait': sys.exit(42 if os.environ['MOCK_MODE']=='rollback-failure' or (os.environ['MOCK_MODE']=='wait-failure' and (p/'updated').exists()) else 0)
elif operation == 'list-tasks':
    print('None' if 'STOPPED' in args else json.dumps({'taskArns':['test-task']}))
elif operation == 'describe-tasks':
    revision = os.environ['NEW_TASK'] if (p/'updated').exists() and os.environ['MOCK_MODE'] != 'rollback' else json.loads((p/'service.json').read_text())['services'][0]['taskDefinition']
    healthy = not ((p/'updated').exists() and os.environ['MOCK_MODE'] == 'container-failure')
    print(json.dumps({'tasks':[{'taskDefinitionArn':revision, 'lastStatus':'RUNNING', 'containers':[{'name':name,'lastStatus':'RUNNING','healthStatus':'HEALTHY' if healthy else 'UNHEALTHY'} for name in ['frontend','user-service','payment-service']]}]}))
elif operation == 'describe-target-health': print(json.dumps({'TargetHealthDescriptions':[{'TargetHealth':{'State':'unhealthy' if (p/'updated').exists() and os.environ['MOCK_MODE']=='alb-failure' else 'healthy'}}]}))
else: sys.exit('Unexpected AWS operation: '+operation)
''')
            mock.chmod(0o755)
            curl = root / "curl"
            curl.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
new = (pathlib.Path(os.environ['MOCK_DIR'])/'updated').exists()
if new and os.environ['MOCK_MODE'] == 'https-failure': sys.exit(60)
url = sys.argv[-1]
enabled = new and os.environ['MOCK_MODE'] != 'rollback' and os.environ['STRIPE_TEST_MODE'] == 'true'
if new and os.environ['MOCK_MODE'] == 'feature-mismatch': enabled = not enabled
print('false-body\\n401' if url.endswith('/transactions') else json.dumps({'tipPoolsEnabled':enabled})+'\\n200' if url.endswith('/features') else '<html>Gratilo</html>\\n200', end='')
''')
            curl.chmod(0o755)
            environment = {**self.config, "STRIPE_TEST_MODE": "true" if stripe_test else "false",
                           "GITHUB_EVENT_NAME": "workflow_dispatch" if stripe_test else "push",
                           "GITHUB_REF": "refs/heads/main" if stripe_test else "refs/heads/feature",
                           "STRIPE_TEST_CONFIRMATION": test_confirmation,
                           "PATH": folder + os.pathsep + "/usr/bin:/bin", "MOCK_DIR": folder,
                           "NEW_TASK": self.new, "MOCK_MODE": mode, "GITHUB_STEP_SUMMARY": str(root / "summary")}
            environment.update({name.upper().replace("-", "_") + "_IMAGE": value for name, value in self.images.items()})
            result = subprocess.run(["bash", "deployment/scripts/deploy_ecs.sh"], cwd=ROOT, env=environment,
                                    text=True, capture_output=True, timeout=20)
            calls = (root / "calls").read_text() if (root / "calls").exists() else ""
            return result, calls

    def test_full_deployment_with_mocked_aws(self):
        result, calls = self.run_mock_deployment("success")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, calls.splitlines().count("register-task-definition"))
        self.assertEqual(1, calls.splitlines().count("update-service"))

    def test_stripe_test_deployment_dry_run_with_mocked_aws(self):
        result, calls = self.run_mock_deployment("success", stripe_test=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, calls.splitlines().count("register-task-definition"))
        self.assertEqual(1, calls.splitlines().count("update-service"))

    def test_normal_release_disables_inherited_stripe_test_payments(self):
        result, calls = self.run_mock_deployment("success", source_stripe_test=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, calls.splitlines().count("update-service"))

    def test_waiter_returning_before_rollout_completion_does_not_trigger_rollback(self):
        result, calls = self.run_mock_deployment("rollout-state-lag", stripe_test=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("waiting for the selected revision rollout", result.stdout)
        self.assertEqual(1, calls.splitlines().count("update-service"))

    def test_stripe_feature_mismatch_rolls_back_and_verifies_previous_disabled_revision(self):
        result, calls = self.run_mock_deployment("feature-mismatch", stripe_test=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("BACKEND_RESPONSE", result.stderr)
        self.assertIn("Rollback succeeded", result.stderr)
        self.assertEqual(2, calls.splitlines().count("update-service"))

    def test_stripe_test_deployment_requires_its_dedicated_confirmation_before_aws(self):
        result, calls = self.run_mock_deployment("success", stripe_test=True, test_confirmation="RELEASE DEPLOY gratilo-production")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("dedicated confirmation", result.stderr)
        self.assertEqual("", calls)

    def test_stable_rollback_is_a_failure(self):
        result, calls = self.run_mock_deployment("rollback")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("stable rollback", result.stderr)
        self.assertIn("list-tasks", calls)

    def test_wait_failure_remains_failure_and_collects_diagnostics(self):
        result, calls = self.run_mock_deployment("wait-failure")
        self.assertEqual(42, result.returncode)
        self.assertIn("list-tasks", calls)
        self.assertIn("Rollback succeeded", result.stderr)
        self.assertEqual(2, calls.splitlines().count("update-service"))

    def test_container_or_https_failure_rolls_back_but_stays_failed(self):
        for mode in ("container-failure", "https-failure", "alb-failure", "iam-denied-after-update"):
            with self.subTest(mode=mode):
                result, calls = self.run_mock_deployment(mode)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Rollback succeeded", result.stderr)
                self.assertEqual(2, calls.splitlines().count("update-service"))

    def test_missing_health_permissions_abort_before_any_resource_mutation(self):
        result, calls = self.run_mock_deployment("iam-denied-preflight")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("IAM_ACCESS_DENIED: ecs:ListTasks", result.stderr)
        self.assertNotIn("register-task-definition", calls)
        self.assertNotIn("update-service", calls)

    def test_restored_rollback_is_not_reported_verified_when_diagnosis_remains_denied(self):
        result, calls = self.run_mock_deployment("iam-denied-persistent")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("IAM_ACCESS_DENIED: ecs:ListTasks", result.stderr)
        self.assertIn("Rollback service stabilized", result.stdout)
        self.assertIn("ROLLBACK NOT VERIFIED", result.stderr)
        self.assertEqual(2, calls.splitlines().count("update-service"))

    def test_never_rolls_back_over_foreign_revision(self):
        result, calls = self.run_mock_deployment("foreign-revision")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Rollback refused", result.stderr)
        self.assertEqual(1, calls.splitlines().count("update-service"))

    def test_digest_conflict_aborts_before_any_mutation(self):
        result, calls = self.run_mock_deployment("digest-conflict")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("register-task-definition", calls)
        self.assertNotIn("update-service", calls)

    def test_failed_rollback_is_explicit(self):
        result, calls = self.run_mock_deployment("rollback-failure")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("ROLLBACK NOT VERIFIED", result.stderr)
        self.assertEqual(2, calls.splitlines().count("update-service"))


if __name__ == "__main__":
    unittest.main()
