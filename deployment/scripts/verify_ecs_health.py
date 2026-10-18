"""Read-only production readiness checks; no login, registration, email or payment calls."""
import json
import os
import subprocess
import sys


def aws(*arguments):
    result = subprocess.run(["aws", *arguments, "--output", "json"], capture_output=True, text=True)
    if result.returncode:
        raise ValueError("AWS health query failed: " + arguments[1])
    return json.loads(result.stdout)


def validate_tasks(response, revision, desired):
    tasks = response.get("tasks", [])
    if response.get("failures") or len(tasks) != desired:
        raise ValueError("Unexpected running task count")
    for task in tasks:
        if task.get("taskDefinitionArn") != revision or task.get("lastStatus") != "RUNNING":
            raise ValueError("Running task does not use the selected revision")
        containers = {c["name"]: c for c in task.get("containers", [])}
        for name in ("frontend", "user-service", "payment-service"):
            container = containers.get(name, {})
            if container.get("lastStatus") != "RUNNING" or container.get("healthStatus") != "HEALTHY":
                raise ValueError("Container is not healthy: " + name)


def validate_targets(response):
    states = [entry.get("TargetHealth", {}).get("State") for entry in response.get("TargetHealthDescriptions", [])]
    if "healthy" not in states or any(state not in ("healthy", "draining") for state in states):
        raise ValueError("ALB targets are not healthy")


def curl(path):
    # Fixed HTTPS origin, default CA/hostname verification, no redirects accepted.
    result = subprocess.run(["curl", "--silent", "--show-error", "--max-time", "30",
                             "--proto", "=https", "--write-out", "\n%{http_code}",
                             "https://gratilo.eu" + path], capture_output=True, text=True)
    if result.returncode:
        raise ValueError("HTTPS request failed; response details suppressed")
    body, status = result.stdout.rsplit("\n", 1)
    return body, status


def main():
    cluster, service = os.environ["ECS_CLUSTER"], os.environ["ECS_SERVICE"]
    revision = sys.argv[1]
    details = aws("ecs", "describe-services", "--cluster", cluster, "--services", service)["services"][0]
    arns = aws("ecs", "list-tasks", "--cluster", cluster, "--service-name", service, "--desired-status", "RUNNING")["taskArns"]
    if not arns:
        raise ValueError("No running tasks")
    validate_tasks(aws("ecs", "describe-tasks", "--cluster", cluster, "--tasks", *arns), revision, details["desiredCount"])
    targets = [lb["targetGroupArn"] for lb in details.get("loadBalancers", []) if lb.get("containerName") == "frontend" and lb.get("containerPort") == 80]
    if len(targets) != 1:
        raise ValueError("Expected exactly one frontend target group")
    for target in targets:
        validate_targets(aws("elbv2", "describe-target-health", "--target-group-arn", target))
    if curl("/")[1] != "200":
        raise ValueError("Frontend HTTPS did not return 200")
    body, status = curl("/user-service/features")
    if status != "200" or json.loads(body) != {"tipPoolsEnabled": False}:
        raise ValueError("User-service feature endpoint failed")
    if curl("/payment-service/transactions")[1] not in ("401", "403"):
        raise ValueError("Protected payment endpoint did not reject anonymous GET")
    print("Selected revision: healthy containers, healthy ALB target, HTTPS frontend and both API routes verified.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, IndexError, OSError):
        sys.exit("Post-deployment health verification failed; no response bodies or credentials logged.")
