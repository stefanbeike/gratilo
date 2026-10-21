"""Render release images and selected payment mode into the existing shared task."""
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
             "STRIPE_SECRET_KEY", "STRIPE_WEBHOOK_SECRET", "STRIPE_DIRECT_WEBHOOK_SECRET", "STRIPE_CONNECT_WEBHOOK_SECRET", "PAYMENTS_TEST_ACTIVATION_CODE",
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
    test_mode = config.get("STRIPE_TEST_MODE", "false")
    require(test_mode in ("true", "false"), "STRIPE_TEST_MODE must be explicitly true or false")
    stripe_test_mode = test_mode == "true"
    pricing_mode=config.get("GRATILO_NEW_PRICING_ENABLED","false")
    require(pricing_mode in ("true","false"),"GRATILO_NEW_PRICING_ENABLED must be explicitly true or false")
    require(pricing_mode!="true" or stripe_test_mode,"New pricing can only be enabled in Stripe test mode")
    pools_enabled = "true" if stripe_test_mode else "false"
    require(all(sum(c.get("name") == name for c in definitions) == 1 for name in CONTAINERS), "Task must contain each application container exactly once")
    registry = f'{config["AWS_ACCOUNT_ID"]}.dkr.ecr.{config["AWS_REGION"]}.amazonaws.com'
    for container in definitions:
        name = container["name"]
        if name not in CONTAINERS:
            continue  # Preserve unrelated sidecars as well.
        if name in ("user-service", "payment-service"):
            # Keep the UI feature endpoint and payment backend in lockstep.
            environment = container.get("environment", [])
            require(len({e["name"] for e in environment}) == len(environment), "Duplicate environment variable: " + name)
            values = {item["name"]: item["value"] for item in environment}
            values["FEATURE_TIP_POOLS_ENABLED"] = pools_enabled
            if name == "payment-service":
                # The selected release mode is authoritative, not the previous
                # deployment's flags. Disabled releases must work after test runs.
                values.update(PAYMENTS_ENABLED=pools_enabled,
                              STRIPE_SANDBOX_PAYOUTS=pools_enabled,
                              STRIPE_TEST_ONLY="true", STRIPE_DEMO_ENABLED="false")
                # A release may explicitly enable the versioned model; inherited true is never implicit approval.
                if "GRATILO_NEW_PRICING_ENABLED" in config or "GRATILO_NEW_PRICING_ENABLED" in values:
                    values["GRATILO_NEW_PRICING_ENABLED"]=pricing_mode
                if "PAYMENTS_TEST_USER_IDS" in values:
                    values["PAYMENTS_TEST_USER_IDS"] = ""
            container["environment"] = [{"name": key, "value": value} for key, value in values.items()]
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
        prefix = f'arn:aws:secretsmanager:{config["AWS_REGION"]}:{config["AWS_ACCOUNT_ID"]}:secret:'
        require(not SENSITIVE.intersection(env), "Credentials must use ECS secrets: " + name)
        require(not set(env).intersection(secret), "Environment/secret name collision: " + name)
        if name == "frontend":
            require(all(env.get(key) in ("127.0.0.1", "localhost") for key in ("USER_SERVICE_HOST", "PAYMENT_SERVICE_HOST")), "Shared task requires frontend backend hosts 127.0.0.1")
            dependencies = {d["containerName"]: d["condition"] for d in container.get("dependsOn", [])}
            require(all(dependencies.get(key) == "HEALTHY" for key in CONTAINERS[1:]), "Frontend must wait for both healthy backends")
        else:
            require("prd" in env.get("MICRONAUT_ENVIRONMENTS", "").split(","), "Missing production profile: " + name)
            require(env.get("FRONTEND_BASE_URL", "https://gratilo.eu") == "https://gratilo.eu", "Wrong production domain: " + name)
            require(env.get("FEATURE_TIP_POOLS_ENABLED", "false").lower() == pools_enabled, "Pool feature must match the explicitly selected Stripe test mode")
            db = urlparse(env.get("DATASOURCES_DEFAULT_URL", "").removeprefix("jdbc:"))
            query = parse_qs(db.query)
            require(db.scheme == "postgresql" and db.path == "/gratilodb" and db.hostname not in (None, "localhost", "127.0.0.1", "postgres"), "Expected private RDS URL for existing gratilodb: " + name)
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
                require(env.get("USER_SERVICE_URL", "http://localhost:8081") in ("http://127.0.0.1:8081", "http://localhost:8081"), "Wrong shared-task user service URL")
                demo_enabled = env.get("STRIPE_DEMO_ENABLED", "false").lower() == "true"
                sandbox_payouts = env.get("STRIPE_SANDBOX_PAYOUTS", "false").lower() == "true"
                payments_enabled = env.get("PAYMENTS_ENABLED", "false").lower() == "true"
                if test_mode == "true":
                    require(payments_enabled, "Stripe test mode requires PAYMENTS_ENABLED=true")
                    require(env.get("STRIPE_TEST_ONLY", "false").lower() == "true", "Stripe test mode requires STRIPE_TEST_ONLY=true")
                    require(not demo_enabled, "Demo payment mode must remain disabled")
                    # true means the payout endpoint only writes a local sandbox ledger entry;
                    # false calls Stripe's /v1/payouts API. Keep external payouts unreachable.
                    require(sandbox_payouts, "Stripe test mode requires simulated payouts; Stripe payout API must remain disabled")
                    require(env.get("PAYMENTS_TEST_USER_IDS", "") == "", "Use the account-bound activation code instead of a UUID allowlist")
                    stripe_secret = secret.get("STRIPE_SECRET_KEY", "")
                    webhook_secret = secret.get("STRIPE_WEBHOOK_SECRET", "")
                    activation_secret = secret.get("PAYMENTS_TEST_ACTIVATION_CODE", "")
                    test_secret_prefix = prefix + "gratilo/prd/stripe-test-"
                    require(stripe_secret.startswith(test_secret_prefix) and stripe_secret.endswith(":secret_key::"), "Stripe test key must come from the dedicated test secret JSON field")
                    require(webhook_secret.startswith(test_secret_prefix) and webhook_secret.endswith(":webhook_secret::"), "Stripe webhook signature secret must come from the dedicated test secret JSON field")
                    require(activation_secret.startswith(test_secret_prefix) and activation_secret.endswith(":activation_code::"), "Stripe test access requires the private activation-code secret JSON field")
                    stripe_arns = {value.rsplit(":", 3)[0] for value in (stripe_secret, webhook_secret, activation_secret)}
                    require(len(stripe_arns) == 1, "Stripe key, webhook, and activation code must use the same test secret")
                    if pricing_mode=="true":
                        direct_reference=next(iter(stripe_arns))+":direct_webhook_secret::"
                        if "STRIPE_DIRECT_WEBHOOK_SECRET" in secret:
                            require(secret["STRIPE_DIRECT_WEBHOOK_SECRET"]==direct_reference,"Direct webhook must use the same dedicated Stripe test secret")
                        secret["STRIPE_DIRECT_WEBHOOK_SECRET"]=direct_reference
                        container["secrets"]=[{"name":key,"valueFrom":value} for key,value in secret.items()]
                        env.update(GRATILO_PLATFORM_FEE_PERCENT="5.00",GRATILO_PLATFORM_FEE_FIXED_MINOR="0")
                else:
                    require(not sandbox_payouts and not demo_enabled, "Payments and simulated payouts must remain disabled")
                    require(not payments_enabled, "Payments must remain disabled")
                # Always replace inherited redirects in the rendered candidate. This
                # safely repairs older task definitions while never accepting a
                # caller-controlled or cross-domain Stripe return URL.
                redirects = {
                    "STRIPE_SUCCESS_URL": "https://gratilo.eu/pay/success?session_id={CHECKOUT_SESSION_ID}",
                    "STRIPE_CANCEL_URL": "https://gratilo.eu/pay/cancelled?session_id={CHECKOUT_SESSION_ID}",
                }
                for key, expected in redirects.items():
                    env[key] = expected
                container["environment"] = [{"name": key, "value": value} for key, value in env.items()]
            require(required.issubset(secret), "Required Secrets Manager references missing: " + name)
            require(all(secret[key].startswith(prefix) for key in required), "Secrets must reference Secrets Manager in deployment account/region")
        container["image"] = images[name]
    backends = [next(c for c in definitions if c["name"] == name) for name in CONTAINERS[1:]]
    for shared in ("JWT_SECRET", "INTERNAL_API_KEY"):
        refs = [{s["name"]: s["valueFrom"] for s in c.get("secrets", [])}[shared] for c in backends]
        require(refs[0] == refs[1], shared + " must use the same secret reference in both backends")
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
