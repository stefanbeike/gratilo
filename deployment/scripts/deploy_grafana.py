"""Render and release only the pre-provisioned private Grafana ECS service."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.parse import urlsplit

from semver_release import REGISTRY, stable_version

ACCOUNT = "070351676632"
REGION = "eu-central-1"
CLUSTER = "gratilo-prd-cluster"
APP = "gratilo-prd-app"
SERVICE = "gratilo-prd-grafana"
PFX = f"arn:aws:ecs:{REGION}:{ACCOUNT}:"
SECRET_PFX = f"arn:aws:secretsmanager:{REGION}:{ACCOUNT}:secret:gratilo/prd/"
ELB_PFX = f"arn:aws:elasticloadbalancing:{REGION}:{ACCOUNT}:"
TEMPLATE = Path(__file__).resolve().parents[1] / "ecs/gratilo-prd-grafana.task-definition.json"
PROD_APP_CONTAINERS = ("frontend", "user-service", "payment-service")


class DeployError(ValueError):
    pass


def check(ok, message):
    if not ok:
        raise DeployError(message)


def secret(ref, name):
    check(re.fullmatch(re.escape(SECRET_PFX + name) + r"-[A-Za-z0-9]{6}", ref),
          "Expected full production Secrets Manager ARN for " + name)
    return ref


def hostname(value):
    return len(value) <= 253 and re.fullmatch(
        r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+", value)


def https_url(value, root=False):
    check(not re.search(r"[^a-zA-Z0-9./:_-]", value), "Grafana URLs must be plain private HTTPS endpoints")
    parsed = urlsplit(value)
    check(parsed.scheme == "https" and hostname(parsed.hostname or "") and parsed.port in (None, 443)
          and not parsed.username and not parsed.password and not parsed.query and not parsed.fragment
          and (not root or parsed.path in ("", "/")), "Invalid HTTPS endpoint for Grafana")
    return parsed


def config(env):
    required = ("GRAFANA_ROOT_URL", "GRAFANA_RDS_HOST", "GRAFANA_PROMETHEUS_URL", "GRAFANA_LOKI_URL",
                "GRAFANA_SECRET_ARN", "GRAFANA_HTTPS_RULE_ARN", "OBS_APP_SCRAPE_SECRET_ARN")
    for name in required:
        check(env.get(name), "Missing production environment variable: " + name)
    values = {name: env[name] for name in required}
    https_url(values["GRAFANA_ROOT_URL"], root=True)
    for key in ("GRAFANA_PROMETHEUS_URL", "GRAFANA_LOKI_URL"):
        https_url(values[key])
    db = values["GRAFANA_RDS_HOST"]
    check(hostname(db) and db.endswith("." + REGION + ".rds.amazonaws.com"), "Use the private regional RDS endpoint for TLS")
    secret(values["GRAFANA_SECRET_ARN"], "grafana")
    secret(values["OBS_APP_SCRAPE_SECRET_ARN"], "observability-scrape")
    check(re.fullmatch(re.escape(ELB_PFX) + r"listener-rule/app/[A-Za-z0-9-]+/(?:[a-f0-9]{16}/){2}[a-f0-9]{16}",
                       values["GRAFANA_HTTPS_RULE_ARN"]), "Use the production ALB listener rule ARN")
    return values


def render(values, image, version):
    check(stable_version(version) is not None and len(version) <= 128, "Expected stable Grafana release version")
    check(re.fullmatch(re.escape(REGISTRY + "/gratilo/grafana@sha256:") + r"[a-f0-9]{64}", image),
          "Expected the exact private Grafana image digest")
    text = TEMPLATE.read_text()
    for key, value in {**values, "GRAFANA_IMAGE": image}.items():
        text = text.replace("<" + key + ">", value)
    check("<" not in text, "Unresolved Grafana task placeholder")
    task = json.loads(text)
    task["tags"] = [{"key": "Application", "value": SERVICE}, {"key": "GratiloRelease", "value": version}]
    return task


def aws(*args, payload=None):
    command = ["aws", *args, "--region", REGION, "--output", "json", "--no-cli-pager"]
    if payload is not None:
        command += ["--cli-input-json", json.dumps(payload)]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        raise DeployError("AWS CLI unavailable or timed out: " + "/".join(args[:2])) from None
    check(result.returncode == 0, "AWS call failed: " + "/".join(args[:2]) + "; check resource and IAM configuration")
    try:
        return json.loads(result.stdout) if result.stdout.strip() else {}
    except ValueError:
        raise DeployError("AWS response is not JSON: " + "/".join(args[:2])) from None


def describe_service(call, name=SERVICE):
    result = call("ecs", "describe-services", "--cluster", CLUSTER, "--services", name)
    services = result.get("services", [])
    check(not result.get("failures") and len(services) == 1 and services[0].get("status") == "ACTIVE",
          "ECS service unavailable: " + name + "; prepare the approved baseline infrastructure first")
    service = services[0]
    check(service.get("serviceArn") == f"{PFX}service/{CLUSTER}/{name}", "Unexpected ECS service identity")
    check(re.fullmatch(re.escape(PFX + "task-definition/" + name + ":") + r"[1-9][0-9]*",
                       service.get("taskDefinition", "")), "Unexpected ECS task definition")
    return service


def topology(service, call, values):
    network = service.get("networkConfiguration", {}).get("awsvpcConfiguration", {})
    deployment = service.get("deploymentConfiguration", {})
    breaker = deployment.get("deploymentCircuitBreaker", {})
    check(network.get("assignPublicIp") == "DISABLED" and network.get("subnets") and network.get("securityGroups"),
          "Grafana must run in private awsvpc subnets with explicit security groups")
    check(service.get("platformVersion") in ("1.4.0", "LATEST")
          and (service.get("launchType") == "FARGATE" or (
              service.get("capacityProviderStrategy") and
              {p["capacityProvider"] for p in service["capacityProviderStrategy"]} == {"FARGATE"})),
          "Use stable Linux Fargate 1.4.0+ for Grafana")
    check(service.get("deploymentController", {}).get("type") == "ECS"
          and breaker.get("enable") and breaker.get("rollback")
          and deployment.get("minimumHealthyPercent") == 0 and deployment.get("maximumPercent") == 100
          and service["desiredCount"] in (0, 1), "Grafana requires single-task ECS rollout with circuit breaker")
    lbs = service.get("loadBalancers", [])
    check(len(lbs) == 1 and lbs[0].get("containerName") == "grafana" and lbs[0].get("containerPort") == 3000,
          "Attach the private ALB target group to grafana:3000")
    group_arn = lbs[0].get("targetGroupArn", "")
    check(group_arn.startswith(ELB_PFX + "targetgroup/"), "Invalid Grafana target group ARN")
    rule_arn = values["GRAFANA_HTTPS_RULE_ARN"]
    listener_arn = rule_arn.replace(":listener-rule/", ":listener/").rsplit("/", 1)[0]
    rule, = call("elbv2", "describe-rules", "--rule-arns", rule_arn)["Rules"]
    listener, = call("elbv2", "describe-listeners", "--listener-arns", listener_arn)["Listeners"]
    lb_arn = listener["LoadBalancerArn"]
    lb, = call("elbv2", "describe-load-balancers", "--load-balancer-arns", lb_arn)["LoadBalancers"]
    tg, = call("elbv2", "describe-target-groups", "--target-group-arns", group_arn)["TargetGroups"]
    host = https_url(values["GRAFANA_ROOT_URL"], root=True).hostname
    conditions = rule.get("Conditions", [])
    actions = rule.get("Actions", [])
    check(rule.get("RuleArn") == rule_arn and not rule.get("IsDefault")
          and len(conditions) == 1 and conditions[0].get("Field") == "host-header"
          and conditions[0].get("HostHeaderConfig", {}).get("Values", conditions[0].get("Values")) == [host]
          and len(actions) == 1 and actions[0].get("Type") == "forward"
          and actions[0].get("TargetGroupArn", group_arn) == group_arn
          and (actions[0].get("TargetGroupArn") == group_arn or actions[0].get("ForwardConfig"))
          and [g["TargetGroupArn"] for g in actions[0].get("ForwardConfig", {}).get(
              "TargetGroups", [{"TargetGroupArn": group_arn}])] == [group_arn],
          "Grafana requires a dedicated exact-host rule forwarding to its target group")
    check(listener.get("ListenerArn") == listener_arn and listener.get("Protocol") == "HTTPS"
          and listener.get("Port") == 443 and listener.get("Certificates")
          and lb.get("Scheme") == "internal" and lb.get("Type") == "application"
          and tg.get("LoadBalancerArns") == [lb_arn] and tg.get("TargetType") == "ip"
          and tg.get("Port") == 3000 and tg.get("HealthCheckPath") == "/api/health"
          and tg.get("Matcher", {}).get("HttpCode") == "200", "Grafana requires the private HTTPS ALB and exact 200 health check")
    return group_arn


def preflight(call, values):
    check(call("sts", "get-caller-identity")["Account"] == ACCOUNT, "Unexpected AWS account")
    service = describe_service(call)
    group = topology(service, call, values)
    for key in ("GRAFANA_SECRET_ARN", "OBS_APP_SCRAPE_SECRET_ARN"):
        item = call("secretsmanager", "describe-secret", "--secret-id", values[key])
        check(item.get("ARN") == values[key] and not item.get("DeletedDate"), "Production secret missing or scheduled for deletion: " + key)
        check(any("AWSCURRENT" in stages for stages in item.get("VersionIdsToStages", {}).values()),
              "Production secret has no AWSCURRENT version: " + key)
    # Exercise read permissions before either service is mutated.
    call("elbv2", "describe-target-health", "--target-group-arn", group)
    call("ecs", "list-tasks", "--cluster", CLUSTER, "--service-name", SERVICE, "--desired-status", "RUNNING")
    definition = read_task(service["taskDefinition"], call)["taskDefinition"]
    check(definition.get("status") == "ACTIVE", "Keep the previous Grafana task definition active for rollback")
    groups = call("logs", "describe-log-groups", "--log-group-name-prefix", "/ecs/gratilo-prd-grafana")
    check(any(g["logGroupName"] == "/ecs/gratilo-prd-grafana" for g in groups.get("logGroups", [])),
          "Prepare the Grafana CloudWatch log group before rollout")
    return service, group


def read_task(arn, call):
    return call("ecs", "describe-task-definition", "--task-definition", arn, "--include", "TAGS")


def subset(expected, actual):
    if isinstance(expected, dict):
        return isinstance(actual, dict) and all(key in actual and subset(value, actual[key]) for key, value in expected.items())
    if isinstance(expected, list):
        if not isinstance(actual, list) or len(expected) != len(actual):
            return False
        if expected and all(isinstance(item, dict) and "name" in item for item in expected):
            actual = sorted(actual, key=lambda item: item["name"])
            expected = sorted(expected, key=lambda item: item["name"])
        return all(subset(left, right) for left, right in zip(expected, actual))
    return expected == actual


def same_task(current, desired):
    definition = current["taskDefinition"]
    if not subset({k: v for k, v in desired.items() if k != "tags"}, definition):
        return False
    tags = {tag["key"]: tag["value"] for tag in current.get("tags", [])}
    return all(tags.get(tag["key"]) == tag["value"] for tag in desired["tags"])


def app_at_release(call, env):
    service = describe_service(call, APP)
    deployments = service.get("deployments", [])
    check(len(deployments) == 1, "The application rollout must be completed before Grafana")
    current = deployments[0]
    check(current.get("status") == "PRIMARY" and current.get("taskDefinition") == service["taskDefinition"]
          and current.get("rolloutState") == "COMPLETED" and service.get("pendingCount", 0) == 0 and
          service.get("runningCount") == service.get("desiredCount") and service.get("runningCount", 0) > 0,
          "The application must already be healthy at the selected release")
    containers = read_task(service["taskDefinition"], call)["taskDefinition"]["containerDefinitions"]
    images = {item["name"]: item["image"] for item in containers}
    check(all(images.get(name) == env.get(name.upper().replace("-", "_") + "_IMAGE")
              for name in PROD_APP_CONTAINERS), "Grafana retry must match the currently deployed Gratilo release")


def healthy(service, revision, count, group, call):
    primary = [d for d in service.get("deployments", []) if d.get("status") == "PRIMARY"]
    if len(primary) == 1 and primary[0].get("taskDefinition") == revision and primary[0].get("rolloutState") == "FAILED":
        raise DeployError("Grafana ECS circuit breaker reported a failed rollout")
    if (service["taskDefinition"] != revision or len(primary) != 1 or len(service["deployments"]) != 1
            or primary[0].get("rolloutState") != "COMPLETED" or primary[0].get("taskDefinition") != revision
            or service["runningCount"] != count or service["desiredCount"] != count or service.get("pendingCount", 0) != 0):
        return False
    if count == 0:
        return True
    tasks = call("ecs", "list-tasks", "--cluster", CLUSTER, "--service-name", SERVICE, "--desired-status", "RUNNING")
    if len(tasks.get("taskArns", [])) != count:
        return False
    running = call("ecs", "describe-tasks", "--cluster", CLUSTER, "--tasks", *tasks["taskArns"])
    task_list = running.get("tasks", [])
    if running.get("failures") or len(task_list) != count:
        return False
    ips = set()
    for task in task_list:
        containers = task["containers"]
        if (task["taskDefinitionArn"] != revision or task.get("lastStatus") != "RUNNING"
                or task.get("healthStatus") != "HEALTHY"
                or not any(c.get("name") == "grafana" and c.get("healthStatus") == "HEALTHY" for c in containers)):
            return False
        for attachment in task.get("attachments", []):
            if attachment.get("type") == "ElasticNetworkInterface":
                ips.update(detail["value"] for detail in attachment.get("details", [])
                           if detail["name"] == "privateIPv4Address")
    targets = call("elbv2", "describe-target-health", "--target-group-arn", group)["TargetHealthDescriptions"]
    reached = {t["Target"]["Id"] for t in targets
               if t["Target"]["Port"] == 3000 and t["TargetHealth"]["State"] == "healthy"}
    return len(ips) == count and reached == ips


def wait(revision, count, group, call, *, fallback=None, attempts=72, sleep=time.sleep):
    for attempt in range(attempts):
        service = describe_service(call)
        if attempt >= 3 and service["taskDefinition"] != revision:
            if fallback and service["taskDefinition"] == fallback:
                raise DeployError("Grafana ECS reverted to the previous task definition")
            raise DeployError("Grafana ECS points to a different revision during verification")
        if healthy(service, revision, count, group, call):
            return
        if attempt + 1 < attempts:
            sleep(10)
    raise DeployError("Timed out waiting for Grafana ECS readiness and exact ALB target health")


def follow(call, values, desired, report, poll=wait):
    old, group = preflight(call, values)
    previous, old_count = old["taskDefinition"], old["desiredCount"]
    report("Vorherige Grafana-Revision: " + previous + f" (Desired Count {old_count})")
    existing = read_task(previous, call)
    if old_count == 1 and same_task(existing, desired):
        selected = previous
    else:
        selected = call("ecs", "register-task-definition", payload=desired)["taskDefinition"]["taskDefinitionArn"]
        check(re.fullmatch(re.escape(PFX + "task-definition/" + SERVICE + ":") + r"[1-9][0-9]*", selected),
              "AWS did not return a revision for the Grafana family")
    report("Grafana-Zielrevision: " + selected)
    if (selected, 1) == (previous, old_count):
        poll(selected, 1, group, call)
        report("Grafana gesund. Release war bereits aktiv.")
        return

    def update(revision, count):
        call("ecs", "update-service", "--cluster", CLUSTER, "--service", SERVICE,
             "--task-definition", revision, "--desired-count", str(count))

    current = describe_service(call)
    check((current["taskDefinition"], current["desiredCount"]) == (previous, old_count),
          "Grafana changed since preflight. Refusing concurrent service update.")
    try:
        update(selected, 1)
        poll(selected, 1, group, call, fallback=previous if selected != previous else None)
    except (DeployError, KeyError, TypeError, ValueError):
        report("Grafana-Rollout fehlgeschlagen. Prüfe Wiederherstellung der vorherigen Revision.")
        try:
            state = describe_service(call)
            check(state["taskDefinition"] in (previous, selected)
                  and state["desiredCount"] in (old_count, 1), "Concurrent Grafana change. Refusing rollback.")
            if (state["taskDefinition"], state["desiredCount"]) != (previous, old_count):
                update(previous, old_count)
            poll(previous, old_count, group, call)
            report("Grafana-Rollback verifiziert. Die Veröffentlichung der Anwendung bleibt aktiv.")
        except (DeployError, KeyError, TypeError, ValueError):
            report("Grafana-Rollback nicht verifiziert. Operator muss den Service prüfen: " + previous)
        raise
    report("Grafana gesund. ECS-Task, ALB, Dashboards, Alarmregeln und Datenquellen erfolgreich geprüft.")


def report(message):
    print(message)
    if path := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(path, "a", encoding="utf-8") as summary:
            summary.write(message + "\n\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("validate", "render", "preflight", "deploy"))
    args = parser.parse_args()
    try:
        values = config(os.environ)
        if args.action == "validate":
            return
        task = render(values, os.environ.get("GRAFANA_IMAGE", ""), os.environ.get("RELEASE_VERSION", ""))
        if args.action == "render":
            print(json.dumps(task, indent=2))
            return
        if args.action == "preflight":
            preflight(aws, values)
            report("Grafana-Produktionsvoraussetzungen geprüft.")
            return
        app_at_release(aws, os.environ)
        follow(aws, values, task, report)
    except (DeployError, ValueError, KeyError, TypeError, OSError) as error:
        message = "Grafana: " + (str(error) if isinstance(error, DeployError) else "configuration or AWS response invalid")
        if args.action in ("preflight", "deploy"):
            report("Grafana-Schritt fehlgeschlagen: " + message)
        sys.exit(message)


if __name__ == "__main__":
    main()
