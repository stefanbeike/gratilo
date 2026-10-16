"""Validate the existing shared task, then replace only its three image references."""
import copy
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import parse_qs, urlparse

CONTAINERS = ("frontend", "user-service", "payment-service")
READ_ONLY = {"taskDefinitionArn", "revision", "status", "requiresAttributes",
             "compatibilities", "registeredAt", "registeredBy", "deregisteredAt"}
SENSITIVE = {"DATASOURCES_DEFAULT_PASSWORD", "JWT_SECRET", "INTERNAL_API_KEY",
             "STRIPE_SECRET_KEY", "STRIPE_WEBHOOK_SECRET",
             "JAVAMAIL_AUTHENTICATION_USERNAME", "JAVAMAIL_AUTHENTICATION_PASSWORD"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_service(response, config):
    require(not response.get("failures"), "ECS service lookup failed")
    services = response.get("services", [])
    require(len(services) == 1 and services[0].get("status") == "ACTIVE", "Expected one active ECS service")
    service = services[0]
    require(service.get("deploymentController", {}).get("type", "ECS") == "ECS", "Only ECS rolling deployments are supported")
    breaker = service.get("deploymentConfiguration", {}).get("deploymentCircuitBreaker", {})
    require(breaker.get("enable") and breaker.get("rollback"), "Enable deployment circuit breaker with rollback manually")
    require(any(lb.get("containerName") == "frontend" and lb.get("containerPort") == 80
                for lb in service.get("loadBalancers", [])), "Service must attach the ALB to frontend:80")
    require(service.get("platformVersion") not in ("1.0.0", "1.1.0", "1.2.0", "1.3.0"), "Fargate Linux platform 1.4.0+ required")
    return service["taskDefinition"]


def render(response, images, config):
    task = copy.deepcopy(response["taskDefinition"])
    require(task.get("family") == config["ECS_TASK_FAMILY"], "Unexpected task family")
    require(task.get("networkMode") == "awsvpc" and "FARGATE" in task.get("requiresCompatibilities", []), "Expected awsvpc/FARGATE task")
    platform = task.get("runtimePlatform", {})
    require(platform.get("cpuArchitecture", "X86_64") == "X86_64" and platform.get("operatingSystemFamily", "LINUX") == "LINUX", "Images target Linux/X86_64")
    for field, key in [("executionRoleArn", "ECS_TASK_EXECUTION_ROLE_ARN"), ("taskRoleArn", "ECS_TASK_ROLE_ARN")]:
        require(task.get(field) == config[key], "Unexpected ECS role: " + field)
    definitions = task.get("containerDefinitions", [])
    require(all(sum(c.get("name") == name for c in definitions) == 1 for name in CONTAINERS), "Task must contain each application container exactly once")
    registry = f'{config["AWS_ACCOUNT_ID"]}.dkr.ecr.{config["AWS_REGION"]}.amazonaws.com'
    for container in definitions:
        name = container["name"]
        if name not in CONTAINERS:
            continue  # Preserve unrelated sidecars as well.
        require(re.fullmatch(re.escape(registry + "/gratilo/" + name) + r"@sha256:[0-9a-f]{64}", images[name]), "Expected private ECR digest for " + name)
        require(container.get("essential", True), name + " must be essential")
        require(container.get("healthCheck", {}).get("command"), "Missing ECS health check: " + name)
        require(container.get("logConfiguration", {}).get("logDriver") == "awslogs", "Missing awslogs configuration: " + name)
        environment = container.get("environment", [])
        secrets = container.get("secrets", [])
        require(len({e["name"] for e in environment}) == len(environment), "Duplicate environment variable: " + name)
        require(len({s["name"] for s in secrets}) == len(secrets), "Duplicate secret: " + name)
        env = {e["name"]: e["value"] for e in environment}
        secret = {s["name"]: s["valueFrom"] for s in secrets}
        require(not SENSITIVE.intersection(env), "Credentials must use ECS secrets: " + name)
        require(not set(env).intersection(secret), "Environment/secret name collision: " + name)
        if name == "frontend":
            require(all(env.get(key) == "127.0.0.1" for key in ("USER_SERVICE_HOST", "PAYMENT_SERVICE_HOST")), "Shared task requires frontend backend hosts 127.0.0.1")
            dependencies = {d["containerName"]: d["condition"] for d in container.get("dependsOn", [])}
            require(all(dependencies.get(key) == "HEALTHY" for key in CONTAINERS[1:]), "Frontend must wait for both healthy backends")
        else:
            require("prd" in env.get("MICRONAUT_ENVIRONMENTS", "").split(","), "Missing production profile: " + name)
            require(env.get("FRONTEND_BASE_URL", "https://gratilo.eu") == "https://gratilo.eu", "Wrong production domain: " + name)
            require(env.get("FEATURE_TIP_POOLS_ENABLED", "false").lower() == "false", "Pool splitting must remain disabled for this production baseline")
            db = urlparse(env.get("DATASOURCES_DEFAULT_URL", "").removeprefix("jdbc:"))
            query = parse_qs(db.query)
            require(db.scheme == "postgresql" and db.path == "/tipsterdb" and db.hostname not in (None, "localhost", "127.0.0.1", "postgres"), "Expected private RDS URL for existing tipsterdb: " + name)
            require(not db.username and not db.password and not {"password", "user"}.intersection(query), "Do not put credentials into JDBC URL")
            require(query.get("sslmode") == ["verify-full"] and query.get("sslrootcert") == ["/app/rds-ca-bundle.pem"], "RDS requires verify-full and bundled CA")
            required = {"DATASOURCES_DEFAULT_USERNAME", "DATASOURCES_DEFAULT_PASSWORD", "JWT_SECRET", "INTERNAL_API_KEY"}
            if name == "user-service":
                required |= {"JAVAMAIL_AUTHENTICATION_USERNAME", "JAVAMAIL_AUTHENTICATION_PASSWORD"}
                require(env.get("MAIL_HOST", "email-smtp.eu-central-1.amazonaws.com") == "email-smtp.eu-central-1.amazonaws.com", "Wrong SES region/host")
                require(env.get("MAIL_PORT", "587") == "587", "SES must use port 587")
                require(env.get("MAIL_FROM_EMAIL", "noreply@gratilo.eu") == "noreply@gratilo.eu", "Wrong system sender")
                require(env.get("MAIL_GENERAL_FROM_EMAIL", "info@gratilo.eu") == "info@gratilo.eu", "Wrong general sender")
                require(not any(k.startswith("JAVAMAIL_PROPERTIES_") or k in {"MAIL_SMTP_AUTH", "MAIL_SMTP_STARTTLS_ENABLE", "MAIL_SMTP_STARTTLS_REQUIRED"} for k in env), "Remove legacy SMTP overrides from production task")
            else:
                required |= {"STRIPE_SECRET_KEY", "STRIPE_WEBHOOK_SECRET"}
                require(env.get("USER_SERVICE_URL", "http://127.0.0.1:8081") == "http://127.0.0.1:8081", "Wrong shared-task user service URL")
                require(env.get("STRIPE_SANDBOX_PAYOUTS", "false") == "false" and env.get("STRIPE_DEMO_ENABLED", "false") == "false", "Production cannot use simulated payments")
                for key, expected in [("STRIPE_SUCCESS_URL", "https://gratilo.eu/pay/success?session_id={CHECKOUT_SESSION_ID}"), ("STRIPE_CANCEL_URL", "https://gratilo.eu/pay/cancelled")]:
                    require(env.get(key, expected) == expected, "Wrong Stripe redirect: " + key)
            require(required.issubset(secret), "Required Secrets Manager references missing: " + name)
            prefix = f'arn:aws:secretsmanager:{config["AWS_REGION"]}:{config["AWS_ACCOUNT_ID"]}:secret:'
            require(all(secret[key].startswith(prefix) for key in required), "Secrets must reference Secrets Manager in deployment account/region")
        container["image"] = images[name]
    for key in READ_ONLY:
        task.pop(key, None)
    if response.get("tags"):
        task["tags"] = response["tags"]
    return task


if __name__ == "__main__":
    try:
        payload = json.loads(Path(sys.argv[2]).read_text())
        if sys.argv[1] == "service":
            print(validate_service(payload, os.environ))
        else:
            images = {name: os.environ[name.upper().replace("-", "_") + "_IMAGE"] for name in CONTAINERS}
            result = render(payload, images, os.environ)
            Path(sys.argv[3]).write_text(json.dumps(result))
    except (ValueError, KeyError, IndexError) as error:
        # Validation messages contain configuration names, never task payloads.
        print("Deployment validation failed: " + str(error), file=sys.stderr)
        sys.exit(1)
