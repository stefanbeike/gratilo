"""Read-only ECS/ALB/HTTPS checks with safe, categorized diagnostics."""
import argparse
import json
import os
import re
import subprocess
import sys


ACTIONS = {
    ("ecs", "describe-services"): "ecs:DescribeServices",
    ("ecs", "list-tasks"): "ecs:ListTasks",
    ("ecs", "describe-tasks"): "ecs:DescribeTasks",
    ("elbv2", "describe-target-health"): "elasticloadbalancing:DescribeTargetHealth",
}
CONTAINERS = ("frontend", "user-service", "payment-service")


class HealthError(ValueError):
    def __init__(self, category, message):
        self.category = category
        super().__init__(category + ": " + message)


def aws(*arguments):
    action = ACTIONS[arguments[:2]]
    result = subprocess.run(["aws", *arguments, "--output", "json"], capture_output=True, text=True)
    if result.returncode:
        if re.search(r"\b(AccessDenied(?:Exception)?|UnauthorizedOperation)\b", result.stderr):
            raise HealthError("IAM_ACCESS_DENIED", action + " denied; check role policies, boundary, session policy and SCPs. AWS response suppressed.")
        if re.search(r"\b(ExpiredToken(?:Exception)?|InvalidClientTokenId|UnrecognizedClientException)\b", result.stderr):
            raise HealthError("AWS_AUTHENTICATION", action + " could not authenticate. Credential details suppressed.")
        raise HealthError("AWS_API_ERROR", action + " query failed. AWS response suppressed.")
    try:
        response = json.loads(result.stdout)
        if not isinstance(response, dict):
            raise ValueError()
        return response
    except ValueError:
        raise HealthError("AWS_API_ERROR", action + " returned an invalid response; body suppressed.") from None


def validate_service(details, revision):
    deployments = details.get("deployments", [])
    desired = details.get("desiredCount", 0)
    if (details.get("status") != "ACTIVE" or desired < 1
            or details.get("taskDefinition") != revision
            or details.get("runningCount") != desired or details.get("pendingCount", 0) != 0
            or len(deployments) != 1 or deployments[0].get("taskDefinition") != revision
            or deployments[0].get("rolloutState") != "COMPLETED"):
        raise HealthError("ECS_SERVICE_STATE", "Selected revision is not the sole completed deployment with Running=Desired>0 and no pending tasks.")


def validate_tasks(response, revision, desired):
    tasks = response.get("tasks", [])
    if desired < 1 or response.get("failures") or len(tasks) != desired:
        raise HealthError("ECS_TASK_STATE", "Unexpected running task count or ECS task lookup failures.")
    for task in tasks:
        if task.get("taskDefinitionArn") != revision or task.get("lastStatus") != "RUNNING":
            raise HealthError("ECS_TASK_STATE", "Running task does not use the selected revision.")
        containers = {c["name"]: c for c in task.get("containers", [])}
        for name in CONTAINERS:
            container = containers.get(name, {})
            if container.get("lastStatus") != "RUNNING" or container.get("healthStatus") != "HEALTHY":
                raise HealthError("CONTAINER_UNHEALTHY", "Container is missing, not running or not healthy: " + name)
        if task.get("healthStatus", "HEALTHY") != "HEALTHY":
            raise HealthError("ECS_TASK_STATE", "Aggregate task health is not healthy.")


def validate_targets(response):
    states = [entry.get("TargetHealth", {}).get("State") for entry in response.get("TargetHealthDescriptions", [])]
    if "healthy" not in states or any(state not in ("healthy", "draining") for state in states):
        raise HealthError("ALB_UNHEALTHY", "Frontend target group lacks healthy targets or contains unhealthy/unknown targets.")


def curl(path):
    # Fixed origin, default CA/hostname verification, no redirects or real transactions.
    result = subprocess.run(["curl", "--silent", "--show-error", "--connect-timeout", "10", "--max-time", "30",
                             "--proto", "=https", "--write-out", "\n%{http_code}",
                             "https://gratilo.eu" + path], capture_output=True, text=True)
    if result.returncode:
        category = "HTTPS_TLS" if result.returncode in (35, 51, 58, 60, 77, 83, 90, 91) else "HTTPS_NETWORK"
        raise HealthError(category, "HTTPS transport failed; curl code " + str(result.returncode) + ". Response and error details suppressed.")
    try:
        body, status = result.stdout.rsplit("\n", 1)
        if not re.fullmatch(r"[0-9]{3}", status):
            raise ValueError()
        return body, status
    except ValueError:
        raise HealthError("HTTPS_STATUS", "Missing or invalid HTTPS status; response suppressed.") from None


def verify(revision, permissions_only=False):
    cluster, service = os.environ["ECS_CLUSTER"], os.environ["ECS_SERVICE"]
    response = aws("ecs", "describe-services", "--cluster", cluster, "--services", service)
    if response.get("failures") or len(response.get("services", [])) != 1:
        raise HealthError("ECS_SERVICE_STATE", "Expected exactly one ECS service without lookup failures.")
    details = response["services"][0]
    arns = aws("ecs", "list-tasks", "--cluster", cluster, "--service-name", service, "--desired-status", "RUNNING")["taskArns"]
    tasks = aws("ecs", "describe-tasks", "--cluster", cluster, "--tasks", *arns) if arns else None
    targets = [lb["targetGroupArn"] for lb in details.get("loadBalancers", []) if lb.get("containerName") == "frontend" and lb.get("containerPort") == 80]
    if len(targets) != 1:
        raise HealthError("ALB_CONFIGURATION", "Expected exactly one frontend target group on container port 80.")
    target_health = aws("elbv2", "describe-target-health", "--target-group-arn", targets[0])
    if permissions_only:
        # Do not block a repair deployment because the existing application is unhealthy.
        # With no existing tasks, DescribeTasks can only be exercised after deployment.
        if tasks is None:
            print("Health-query preflight: no running tasks; DescribeTasks permission will be checked after deployment.")
        else:
            print("Health-query permission preflight passed for ECS service/tasks and ALB; no resources changed.")
        return
    validate_service(details, revision)
    validate_tasks(tasks or {}, revision, details["desiredCount"])
    validate_targets(target_health)
    if curl("/")[1] != "200":
        raise HealthError("HTTPS_STATUS", "Frontend HTTPS did not return 200.")
    body, status = curl("/user-service/features")
    if status != "200":
        raise HealthError("HTTPS_STATUS", "User-service feature endpoint did not return 200.")
    try:
        features = json.loads(body)
    except ValueError:
        raise HealthError("BACKEND_RESPONSE", "User-service feature endpoint returned invalid JSON; body suppressed.") from None
    if features != {"tipPoolsEnabled": False}:
        raise HealthError("BACKEND_RESPONSE", "User-service feature flags differ from the expected disabled configuration.")
    if curl("/payment-service/transactions")[1] not in ("401", "403"):
        raise HealthError("HTTPS_STATUS", "Protected payment endpoint did not reject anonymous GET.")
    print("Selected revision: healthy containers, healthy ALB target, HTTPS frontend and both API routes verified.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("revision")
    parser.add_argument("--permissions-only", action="store_true")
    args = parser.parse_args(argv)
    try:
        verify(args.revision, args.permissions_only)
    except HealthError as error:
        print(str(error), file=sys.stderr)
        return 1
    except (ValueError, KeyError, IndexError, TypeError, AttributeError, OSError):
        print("HEALTH_CONFIGURATION: health verification could not complete; response bodies and credentials suppressed.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
