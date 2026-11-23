import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "deployment/scripts"))
import deploy_grafana as deploy
import resolve_release_images as release
import stage_grafana


RULE = deploy.ELB_PFX + "listener-rule/app/grafana-private/" + "/".join(("a"*16, "b"*16, "c"*16))
LISTENER = RULE.replace(":listener-rule/", ":listener/").rsplit("/", 1)[0]
LB = deploy.ELB_PFX + "loadbalancer/app/grafana-private/" + "a"*16
TG = deploy.ELB_PFX + "targetgroup/grafana/" + "d"*16
OLD, NEW, UNRELATED = (deploy.PFX + "task-definition/" + deploy.SERVICE + ":" + n for n in ("1", "2", "3"))
SERVICE = f"{deploy.PFX}service/{deploy.CLUSTER}/{deploy.SERVICE}"
IMAGE = deploy.REGISTRY + "/gratilo/grafana@sha256:" + "a"*64
ENV = {
    "GRAFANA_ROOT_URL": "https://grafana.metrics.internal/",
    "GRAFANA_HTTPS_RULE_ARN": RULE,
    "GRAFANA_RDS_HOST": "gratilo-production.abcd.eu-central-1.rds.amazonaws.com",
    "GRAFANA_PROMETHEUS_URL": "https://prometheus.metrics.internal",
    "GRAFANA_LOKI_URL": "https://loki.metrics.internal",
    "GRAFANA_SECRET_ARN": deploy.SECRET_PFX + "grafana-AbCd01",
    "OBS_APP_SCRAPE_SECRET_ARN": deploy.SECRET_PFX + "observability-scrape-AbCd02",
}
DESIRED = deploy.render(deploy.config(ENV), IMAGE, "v1.2.3")


class Cloud:
    def __init__(self):
        self.mutations = []
        self.service = {
            "status": "ACTIVE", "serviceArn": SERVICE, "taskDefinition": OLD,
            "platformVersion": "1.4.0", "launchType": "FARGATE",
            "networkConfiguration": {"awsvpcConfiguration": {"assignPublicIp": "DISABLED",
                "subnets": ["subnet-fixture"], "securityGroups": ["sg-fixture"]}},
            "desiredCount": 1, "runningCount": 1, "pendingCount": 0,
            "deploymentController": {"type": "ECS"},
            "deploymentConfiguration": {"deploymentCircuitBreaker": {"enable": True, "rollback": True},
                                        "minimumHealthyPercent": 0, "maximumPercent": 100},
            "loadBalancers": [{"containerName": "grafana", "containerPort": 3000, "targetGroupArn": TG}],
            "deployments": [{"status": "PRIMARY", "rolloutState": "COMPLETED", "taskDefinition": OLD}],
        }
        self.balance_scheme = "internal"
        self.target_ip = "10.0.0.42"
        self.target_state = "healthy"
        self.secret_current = True
        self.task_healthy = "HEALTHY"
        self.old_task = {"taskDefinition": {"status": "ACTIVE"}, "tags": []}
        self.exists = True

    def __call__(self, subsystem, operation, *args, payload=None):
        op = subsystem + "/" + operation
        if op == "ecs/describe-services":
            return {"services": [copy.deepcopy(self.service)] if self.exists else []}
        if op == "ecs/describe-task-definition":
            return copy.deepcopy(self.old_task)
        if op == "ecs/list-tasks":
            return {"taskArns": ["grafana-task"]}
        if op == "ecs/describe-tasks":
            return {"tasks": [{"taskDefinitionArn": self.service["taskDefinition"], "lastStatus": "RUNNING",
               "healthStatus": self.task_healthy, "containers": [{"name": "grafana", "healthStatus": self.task_healthy}],
               "attachments": [{"type": "ElasticNetworkInterface", "details": [
                   {"name": "privateIPv4Address", "value": "10.0.0.42"}]}]}]}
        if op == "ecs/register-task-definition":
            self.mutations.append((op, payload))
            return {"taskDefinition": {"taskDefinitionArn": NEW}}
        if op == "ecs/update-service":
            self.mutations.append((op, args))
            self.service["taskDefinition"] = args[args.index("--task-definition") + 1]
            self.service["desiredCount"] = int(args[args.index("--desired-count") + 1])
            return {}
        return {
            "sts/get-caller-identity": {"Account": deploy.ACCOUNT},
            "elbv2/describe-rules": {"Rules": [{"RuleArn": RULE, "IsDefault": False,
                "Conditions": [{"Field": "host-header", "HostHeaderConfig": {"Values": ["grafana.metrics.internal"]}}],
                "Actions": [{"Type": "forward", "TargetGroupArn": TG}]}]},
            "elbv2/describe-listeners": {"Listeners": [{"ListenerArn": LISTENER, "Port": 443, "Protocol": "HTTPS",
                "LoadBalancerArn": LB, "Certificates": [{"CertificateArn": "fixture"}]}]},
            "elbv2/describe-load-balancers": {"LoadBalancers": [{"Scheme": self.balance_scheme, "Type": "application"}]},
            "elbv2/describe-target-groups": {"TargetGroups": [{"LoadBalancerArns": [LB], "TargetType": "ip",
                "Port": 3000, "HealthCheckPath": "/api/health", "Matcher": {"HttpCode": "200"}}]},
            "elbv2/describe-target-health": {"TargetHealthDescriptions": [{"Target": {"Id": self.target_ip, "Port": 3000},
                "TargetHealth": {"State": self.target_state}}]},
            "logs/describe-log-groups": {"logGroups": [{"logGroupName": "/ecs/gratilo-prd-grafana"}]},
            "secretsmanager/describe-secret": {"ARN": args[-1] if args else "",
                "VersionIdsToStages": {"revision": ["AWSCURRENT" if self.secret_current else "AWSPENDING"]}},
        }[op]


class GrafanaDeploymentTest(unittest.TestCase):
    def setUp(self):
        self.cloud = Cloud()
        self.report = []
        self.waits = []

    def poll(self, revision, count, group, aws, **kwargs):
        self.assertEqual(TG, group)
        self.waits.append((revision, count))

    def follow(self, poll=None):
        deploy.follow(self.cloud, ENV, DESIRED, self.report.append, poll or self.poll)

    def test_task_renders_only_grafana_with_readiness_and_external_state(self):
        container, = DESIRED["containerDefinitions"]
        self.assertEqual(container["image"], IMAGE)
        self.assertEqual(container["name"], "grafana")
        self.assertEqual(container["healthCheck"]["command"], ["CMD", "/usr/local/bin/gratilo-grafana-ready"])
        self.assertEqual(container["user"], "472:0")
        self.assertNotIn("taskRoleArn", DESIRED)
        env = {x["name"]: x["value"] for x in container["environment"]}
        secret = {x["name"]: x["valueFrom"] for x in container["secrets"]}
        self.assertEqual("postgres", env["GF_DATABASE_TYPE"])
        self.assertEqual("verify-full", env["GF_DATABASE_SSL_MODE"])
        self.assertEqual("false", env["GF_AUTH_ANONYMOUS_ENABLED"])
        self.assertEqual("true", env["GF_SECURITY_COOKIE_SECURE"])
        self.assertFalse(set(env) & set(secret))
        self.assertEqual(secret["OBS_DB_PASSWORD"], ENV["GRAFANA_SECRET_ARN"] + ":aggregate_password::")
        for image in (IMAGE.replace("@sha256:", ":"), IMAGE.replace("/grafana@", "/frontend@")):
            with self.assertRaises(deploy.DeployError):
                deploy.render(ENV, image, "v1.2.3")

    def test_bootstrap_service_has_no_public_endpoint_or_running_tasks(self):
        bootstrap = json.loads((ROOT / "deployment/ecs/gratilo-prd-grafana.service.example.json").read_text())
        self.assertEqual(0, bootstrap["desiredCount"])
        self.assertEqual(deploy.SERVICE, bootstrap["serviceName"])
        self.assertEqual("DISABLED", bootstrap["networkConfiguration"]["awsvpcConfiguration"]["assignPublicIp"])
        self.assertEqual({"enable": True, "rollback": True}, bootstrap["deploymentConfiguration"]["deploymentCircuitBreaker"])

    def test_only_secure_validated_settings_before_aws(self):
        for name, value in (
            ("GRAFANA_ROOT_URL", "http://grafana.metrics.internal"),
            ("GRAFANA_PROMETHEUS_URL", "https://name:password@metrics.internal"),
            ("GRAFANA_RDS_HOST", "localhost"), ("GRAFANA_SECRET_ARN", deploy.SECRET_PFX + "database-AbCd01"),
            ("OBS_APP_SCRAPE_SECRET_ARN", ENV["OBS_APP_SCRAPE_SECRET_ARN"] + ":token::"),
        ):
            with self.subTest(name=name), self.assertRaises(ValueError):
                deploy.config({**ENV, name: value})

    def test_preflight_refuses_missing_service_public_alb_or_missing_current_secret(self):
        for key, value in (("exists", False), ("balance_scheme", "internet-facing"), ("secret_current", False)):
            with self.subTest(key=key), self.assertRaises(deploy.DeployError):
                setattr(self.cloud, key, value)
                try:
                    self.follow()
                finally:
                    self.cloud = Cloud()

    def test_updates_only_grafana_in_exact_release(self):
        self.follow()
        self.assertEqual(self.cloud.mutations[0], ("ecs/register-task-definition", DESIRED))
        self.assertEqual(self.cloud.mutations[1][1], ("--cluster", deploy.CLUSTER, "--service", deploy.SERVICE,
                         "--task-definition", NEW, "--desired-count", "1"))
        self.assertEqual(self.waits, [(NEW, 1)])

    def test_same_healthy_release_is_noop_and_still_verified(self):
        self.cloud.old_task = {"taskDefinition": {**DESIRED, "status": "ACTIVE"}, "tags": DESIRED["tags"]}
        self.follow()
        self.assertEqual(self.cloud.mutations, [])
        self.assertEqual(self.waits, [(OLD, 1)])

    def test_failed_rollout_restores_previous_desired_count_including_bootstrap(self):
        for count in (0, 1):
            with self.subTest(count=count):
                self.cloud = Cloud()
                self.waits.clear()
                self.cloud.service["desiredCount"] = count

                def poll(revision, desired_count, *args, **kwargs):
                    self.poll(revision, desired_count, *args, **kwargs)
                    if revision == NEW:
                        raise deploy.DeployError("fixture timeout")

                with self.assertRaisesRegex(deploy.DeployError, "fixture timeout"):
                    self.follow(poll)
                self.assertEqual(self.waits, [(NEW, 1), (OLD, count)])
                self.assertEqual(self.cloud.mutations[-1][1][-3:], (OLD, "--desired-count", str(count)))

    def test_rollback_respects_unrelated_revision(self):
        def poll(*args, **kwargs):
            self.cloud.service["taskDefinition"] = UNRELATED
            raise deploy.DeployError("fixture timeout")

        with self.assertRaises(deploy.DeployError):
            self.follow(poll)
        self.assertEqual(len(self.cloud.mutations), 2)
        self.assertIn("nicht verifiziert", self.report[-1])

    def test_health_requires_exact_running_task_and_target(self):
        self.assertTrue(deploy.healthy(self.cloud.service, OLD, 1, TG, self.cloud))
        self.cloud.target_ip = "10.0.0.99"
        self.assertFalse(deploy.healthy(self.cloud.service, OLD, 1, TG, self.cloud))
        self.cloud.target_ip = "10.0.0.42"
        self.cloud.task_healthy = "UNKNOWN"
        self.assertFalse(deploy.healthy(self.cloud.service, OLD, 1, TG, self.cloud))
        self.cloud.service["deployments"][0]["rolloutState"] = "FAILED"
        with self.assertRaisesRegex(deploy.DeployError, "circuit breaker"):
            deploy.healthy(self.cloud.service, OLD, 1, TG, self.cloud)

    def test_retry_rejects_non_matching_publish_artifacts_or_application(self):
        release.verify_publish_outputs({"grafana": IMAGE}, "fixture", {"GRAFANA_RETRY_ONLY": "true"})
        with self.assertRaises(ValueError):
            release.verify_publish_outputs({"grafana": IMAGE}, "fixture",
                                           {"GRAFANA_RETRY_ONLY": "true", "EXPECTED_GRAFANA_IMAGE": IMAGE})
        app_arn = deploy.PFX + "task-definition/" + deploy.APP + ":4"
        app_service = {**self.cloud.service, "serviceArn": SERVICE.replace(deploy.SERVICE, deploy.APP),
                       "taskDefinition": app_arn, "deployments": [
                           {"status": "PRIMARY", "taskDefinition": app_arn, "rolloutState": "COMPLETED"}]}
        app_images = {name.upper().replace("-", "_") + "_IMAGE": deploy.REGISTRY + "/" + name + "@sha256:" + "0"*64
                      for name in deploy.PROD_APP_CONTAINERS}
        definitions = [{"name": name, "image": app_images[name.upper().replace("-", "_") + "_IMAGE"]}
                       for name in deploy.PROD_APP_CONTAINERS]

        def api(*args):
            return ({"services": [app_service]} if args[1] == "describe-services"
                    else {"taskDefinition": {"containerDefinitions": definitions}})

        deploy.app_at_release(api, app_images)
        with self.assertRaises(deploy.DeployError):
            deploy.app_at_release(api, {**app_images, "PAYMENT_SERVICE_IMAGE": "different"})

    def test_workflow_contains_grafana_inside_the_existing_approval_job(self):
        workflow = (ROOT / ".github/workflows/deploy-production.yml").read_text()
        self.assertIn("environment: production", workflow)
        self.assertLess(workflow.index("production_approval.py"), workflow.index("configure-aws-credentials"))
        self.assertLess(workflow.index("resolve_release_images.py"), workflow.index("deploy_grafana.py preflight"))
        self.assertLess(workflow.index("deploy_ecs.sh"), workflow.index("deploy_grafana.py deploy"))
        self.assertIn("!inputs.grafana_retry_only", workflow)
        build = (ROOT / ".github/workflows/005_docker_build.yml").read_text()
        self.assertLess(build.index("Scan Grafana"), build.index("Save scanned images"))
        self.assertIn("gratilo/grafana:", build)

    def test_stage_preserves_dashboards_and_rejects_wrong_ca(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            public_ca = root / "rds.pem"
            public_ca.write_text("fixture certificate")
            with self.assertRaises(ValueError):
                stage_grafana.stage(root / "context", public_ca)
            with patch.object(stage_grafana, "CA_SHA256", hashlib.sha256(public_ca.read_bytes()).hexdigest()):
                stage_grafana.stage(root / "context", public_ca)
            original = ROOT / "observability/grafana/dashboards"
            staged = root / "context"
            for path in original.glob("*.json"):
                self.assertEqual(path.read_bytes(), (staged / "dashboards" / path.name).read_bytes())
            self.assertIn("sslmode: verify-full", (staged / "provisioning/datasources/sources.yml").read_text())
            self.assertIn("disableDeletion: true", (staged / "provisioning/dashboards/dashboards.yml").read_text())
            self.assertFalse((staged / "provisioning/alerting/contact-examples.yml").exists())
