#!/usr/bin/env python3
"""Read-only AWS/Stripe audit and local review artifacts. Never performs cloud writes.
SecretString and Stripe responses remain in process memory and are never printed/saved.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

ACCOUNT = "070351676632"
REGION = "eu-central-1"
PROFILE = "gratilo"
CLUSTER = "gratilo-prd-cluster"
SERVICE = "gratilo-prd-app"
SECRET = "gratilo/prd/stripe-test"
WEBHOOK = "https://gratilo.eu/payment-service/webhooks/stripe"
EVENTS = ["checkout.session.completed", "checkout.session.expired",
          "checkout.session.async_payment_succeeded", "checkout.session.async_payment_failed",
          "payment_intent.succeeded", "payment_intent.payment_failed",
          "payment_intent.canceled", "charge.refunded"]
SAFE_ENV = {"PAYMENTS_ENABLED", "STRIPE_TEST_ONLY", "STRIPE_DEMO_ENABLED", "STRIPE_SANDBOX_PAYOUTS",
            "FEATURE_TIP_POOLS_ENABLED", "PAYMENTS_TEST_USER_IDS", "FRONTEND_BASE_URL",
            "STRIPE_SUCCESS_URL", "STRIPE_CANCEL_URL", "USER_SERVICE_URL"}


class AuditError(Exception):
    pass


def aws(service, operation, *args):
    # No shell, debug flag, inherited AWS profile or secret-bearing arguments.
    command = ["aws", "--profile", PROFILE, "--region", REGION, "--no-cli-pager",
               service, operation, *args, "--output", "json"]
    result = subprocess.run(command, capture_output=True, text=True, timeout=90,
                            env={**os.environ, "AWS_PAGER": "", "AWS_CLI_AUTO_PROMPT": "off"})
    if result.returncode:
        code = re.search(r"\((\w+)\) when calling", result.stderr)
        kind = code.group(1) if code else "authentication-or-cli-error"
        raise AuditError(f"AWS {service}/{operation} failed ({kind}); no response values logged")
    try:
        return json.loads(result.stdout)
    except (ValueError, TypeError):
        raise AuditError(f"AWS {service}/{operation} returned invalid JSON") from None


def stripe(path, key, version=None):
    headers = {"Authorization": "Bearer " + key}
    if version:
        headers["Stripe-Version"] = version
    try:
        with urlopen(Request("https://api.stripe.com" + path, headers=headers, method="GET"), timeout=30) as response:
            return json.load(response)
    except HTTPError as error:
        # Do not read/print Stripe error bodies, URLs, headers or credentials.
        raise AuditError(f"Stripe read-only API check failed (HTTP {error.code})") from None
    except (URLError, ValueError):
        raise AuditError("Stripe read-only API check failed (network/JSON)") from None


def validate_secret(payload):
    try:
        values = json.loads(payload["SecretString"])
    except (ValueError, KeyError, TypeError):
        raise AuditError("Stripe secret must contain a JSON SecretString") from None
    for name, prefix in [("secret_key", "sk_test_"), ("webhook_secret", "whsec_")]:
        value = values.get(name)
        if not isinstance(value, str) or not value.startswith(prefix) or len(value) <= len(prefix) or value != value.strip():
            raise AuditError(f"Stripe secret field {name} is missing or has an invalid test format")
    return values


def execution_policy(secret_arn, kms=None):
    policy = {"Version": "2012-10-17", "Statement": [{"Sid": "ReadOnlyGratiloStripeTest",
        "Effect": "Allow", "Action": "secretsmanager:GetSecretValue", "Resource": secret_arn}]}
    if kms and kms.get("KeyManager") == "CUSTOMER":
        policy["Statement"].append({"Sid": "DecryptOnlyStripeTestViaSecretsManager", "Effect": "Allow",
            "Action": "kms:Decrypt", "Resource": kms["Arn"], "Condition": {"StringEquals": {
                "kms:ViaService": f"secretsmanager.{REGION}.amazonaws.com", "kms:EncryptionContext:SecretARN": secret_arn}}})
    return policy


def task_candidate(task, secret_arn, test_users):
    candidate = copy.deepcopy(task)
    for key in ("taskDefinitionArn", "revision", "status", "requiresAttributes", "compatibilities",
                "registeredAt", "registeredBy", "deregisteredAt"):
        candidate.pop(key, None)
    for container in candidate["containerDefinitions"]:
        name = container["name"]
        env = {e["name"]: e["value"] for e in container.get("environment", [])}
        # Refuse to persist an existing task that contains plaintext credentials.
        if any(re.search(r"SECRET|PASSWORD|TOKEN|PRIVATE_KEY|ACCESS_KEY", key) for key in env):
            raise AuditError("Task contains plaintext credential variables; draft refused")
        if name in ("user-service", "payment-service"):
            env["FEATURE_TIP_POOLS_ENABLED"] = "false"
        if name == "payment-service":
            env.update(PAYMENTS_ENABLED="true", STRIPE_TEST_ONLY="true", STRIPE_DEMO_ENABLED="false",
                       STRIPE_SANDBOX_PAYOUTS="true", PAYMENTS_TEST_USER_IDS=",".join(test_users),
                       FRONTEND_BASE_URL="https://gratilo.eu", USER_SERVICE_URL="http://localhost:8081",
                       STRIPE_SUCCESS_URL="https://gratilo.eu/pay/success?session_id={CHECKOUT_SESSION_ID}",
                       STRIPE_CANCEL_URL="https://gratilo.eu/pay/cancelled")
            secrets = {s["name"]: s["valueFrom"] for s in container.get("secrets", [])}
            secrets.update(STRIPE_SECRET_KEY=secret_arn + ":secret_key::",
                           STRIPE_WEBHOOK_SECRET=secret_arn + ":webhook_secret::")
            container["secrets"] = [{"name": k, "valueFrom": v} for k, v in secrets.items()]
        container["environment"] = [{"name": k, "value": v} for k, v in env.items()]
    return candidate


def network_audit(service):
    config = service.get("networkConfiguration", {}).get("awsvpcConfiguration", {})
    result = {"assignPublicIp": config.get("assignPublicIp"), "subnets": [], "securityGroups": []}
    subnet_ids = config.get("subnets", [])
    if subnet_ids:
        subnets = aws("ec2", "describe-subnets", "--subnet-ids", *subnet_ids)["Subnets"]
        for subnet in subnets:
            tables = aws("ec2", "describe-route-tables", "--filters", "Name=association.subnet-id,Values=" + subnet["SubnetId"])["RouteTables"]
            if not tables:
                tables = aws("ec2", "describe-route-tables", "--filters", "Name=vpc-id,Values=" + subnet["VpcId"], "Name=association.main,Values=true")["RouteTables"]
            defaults = [{k: route[k] for k in ("DestinationCidrBlock", "DestinationIpv6CidrBlock", "GatewayId", "NatGatewayId", "TransitGatewayId", "State") if k in route}
                        for table in tables for route in table.get("Routes", [])
                        if route.get("DestinationCidrBlock") == "0.0.0.0/0" or route.get("DestinationIpv6CidrBlock") == "::/0"]
            result["subnets"].append({"id": subnet["SubnetId"], "vpc": subnet["VpcId"], "defaultRoutes": defaults})
    groups = config.get("securityGroups", [])
    if groups:
        response = aws("ec2", "describe-security-groups", "--group-ids", *groups)
        result["securityGroups"] = [{"id": g["GroupId"], "egress": g.get("IpPermissionsEgress", []),
                                     "ingress": g.get("IpPermissions", [])} for g in response["SecurityGroups"]]
    result["limitation"] = "Route/SG configuration only; NAT health, NACLs, DNS and runtime TLS egress require separate verification."
    return result


def prepare(output, test_users, check_stripe=False):
    import uuid
    users = [str(uuid.UUID(value)) for value in test_users]
    identity = aws("sts", "get-caller-identity")
    if identity.get("Account") != ACCOUNT:
        raise AuditError("AWS account mismatch; no further queries performed")
    services = aws("ecs", "describe-services", "--cluster", CLUSTER, "--services", SERVICE)
    if services.get("failures") or len(services.get("services", [])) != 1:
        raise AuditError("Expected ECS service unavailable")
    service = services["services"][0]
    task = aws("ecs", "describe-task-definition", "--task-definition", service["taskDefinition"])["taskDefinition"]
    secret = aws("secretsmanager", "describe-secret", "--secret-id", SECRET)
    arn = secret["ARN"]
    if not re.fullmatch(f"arn:aws:secretsmanager:{REGION}:{ACCOUNT}:secret:gratilo/prd/stripe-test-[A-Za-z0-9]{{6}}", arn):
        raise AuditError("Unexpected Stripe secret ARN")
    values = validate_secret(aws("secretsmanager", "get-secret-value", "--secret-id", arn, "--version-stage", "AWSCURRENT"))
    role = task["executionRoleArn"]
    if not role.startswith(f"arn:aws:iam::{ACCOUNT}:role/"):
        raise AuditError("Unexpected execution role account")
    role_name = role.rsplit("/", 1)[-1]
    role_meta = aws("iam", "get-role", "--role-name", role_name)["Role"]
    kms = None
    if secret.get("KmsKeyId"):
        kms = aws("kms", "describe-key", "--key-id", secret["KmsKeyId"])["KeyMetadata"]
    report = {"account": ACCOUNT, "region": REGION, "callerArn": identity["Arn"],
              "service": SERVICE, "taskDefinition": service["taskDefinition"], "executionRoleArn": role,
              "taskRoleArn": task.get("taskRoleArn"), "secretArn": arn,
              "secretFields": {"secret_key": "present/test-format", "webhook_secret": "present/signing-format"},
              "kms": {k: kms[k] for k in ("Arn", "KeyManager", "KeyState") if k in kms} if kms else "AWS managed Secrets Manager key (default)",
              "serviceCounts": {k: service.get(k) for k in ("desiredCount", "runningCount", "pendingCount", "platformVersion")},
              "permissionsBoundary": role_meta.get("PermissionsBoundary"), "containers": []}
    for c in task["containerDefinitions"]:
        report["containers"].append({"name": c["name"], "image": c["image"],
            "environment": {e["name"]: e["value"] for e in c.get("environment", []) if e["name"] in SAFE_ENV},
            "secretNames": [s["name"] for s in c.get("secrets", [])]})
    # The simulator includes identity policies/boundaries, not an actual ECS/KMS read.
    try:
        simulation = aws("iam", "simulate-principal-policy", "--policy-source-arn", role,
                         "--action-names", "secretsmanager:GetSecretValue", "--resource-arns", arn)
        report["secretReadSimulation"] = [{"decision": r["EvalDecision"], "missingContext": r.get("MissingContextValues", [])} for r in simulation["EvaluationResults"]]
    except AuditError:
        report["secretReadSimulation"] = "unavailable; role policies require manual review"
    decisions = report["secretReadSimulation"]
    report["iamPlan"] = ("no additional secret-read allow needed" if isinstance(decisions, list) and all(d["decision"] == "allowed" for d in decisions)
                         else "review proposed exact-secret policy; explicit denies, boundaries and SCPs cannot be fixed by an additional allow")
    if kms and kms.get("KeyManager") == "CUSTOMER":
        report["kmsKeyPolicyReview"] = "Confirm key policy permits this role or delegates to account IAM; no KMS policy change is prepared automatically."
    report["executionRoleTrustedServices"] = sorted({service for statement in role_meta.get("AssumeRolePolicyDocument", {}).get("Statement", [])
        for service in ([statement.get("Principal", {}).get("Service")] if isinstance(statement.get("Principal", {}).get("Service"), str)
                        else statement.get("Principal", {}).get("Service", []))})
    report["inlinePolicyNames"] = aws("iam", "list-role-policies", "--role-name", role_name)["PolicyNames"]
    report["attachedPolicyArns"] = [p["PolicyArn"] for p in aws("iam", "list-attached-role-policies", "--role-name", role_name)["AttachedPolicies"]]
    report["network"] = network_audit(service)
    report["loadBalancers"] = []
    for binding in service.get("loadBalancers", []):
        group = aws("elbv2", "describe-target-groups", "--target-group-arns", binding["targetGroupArn"])["TargetGroups"][0]
        for lb_arn in group.get("LoadBalancerArns", []):
            lb = aws("elbv2", "describe-load-balancers", "--load-balancer-arns", lb_arn)["LoadBalancers"][0]
            listeners = aws("elbv2", "describe-listeners", "--load-balancer-arn", lb_arn)["Listeners"]
            report["loadBalancers"].append({"dns": lb["DNSName"], "scheme": lb["Scheme"],
                "targetContainer": binding.get("containerName"), "targetPort": binding.get("containerPort"),
                "listeners": [{"port": l["Port"], "protocol": l["Protocol"],
                    "defaultActionTypes": [a["Type"] for a in l.get("DefaultActions", [])]} for l in listeners]})
    report["ecr"] = [{"name": r["repositoryName"], "tagMutability": r.get("imageTagMutability"),
                      "scanOnPush": r.get("imageScanningConfiguration", {}).get("scanOnPush")}
                     for r in aws("ecr", "describe-repositories", "--repository-names", *["gratilo/" + c for c in ("frontend", "user-service", "payment-service")])["repositories"]]
    if check_stripe:
        balance = stripe("/v1/balance", values["secret_key"])
        if balance.get("livemode") is not False:
            raise AuditError("Stripe test-mode verification failed")
        endpoints = stripe("/v1/webhook_endpoints?limit=100", values["secret_key"])
        matches = [e for e in endpoints.get("data", []) if e.get("url") == WEBHOOK]
        report["stripe"] = {"testModeVerified": True, "matchingSnapshotEndpoints": [
            {"id": e["id"], "status": e["status"], "apiVersion": e.get("api_version"),
             "testMode": e.get("livemode") is False, "missingEvents": sorted(set(EVENTS) - set(e.get("enabled_events", []))) if "*" not in e.get("enabled_events", []) else []} for e in matches],
             "signingSecretMatch": "not provable from read API; verify a genuine signed Stripe delivery after approved deployment"}
        try:
            stripe("/v2/core/accounts?limit=1", values["secret_key"], "2026-09-30.endive")
            report["stripe"]["accountsV2Read"] = "available"
        except AuditError:
            report["stripe"]["accountsV2Read"] = "unavailable; no account created"
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    def write(name, content):
        path = output / name
        descriptor = os.open(path, os.O_CREAT | os.O_TRUNC | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            stream.write(json.dumps(content, indent=2) + "\n")
    write("audit.json", report)
    write("execution-policy.proposed.json", execution_policy(arn, kms))
    if users:
        write("task-definition.proposed.json", task_candidate(task, arn, users))
    # No full response, environment, secret value or exception detail reaches stdout.
    print("Read-only audit complete. Review local audit/policy artifacts; no AWS writes performed.")
    if not users:
        print("Task draft pending: supply authorized test-user UUIDs with --test-user-id.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=Path(".local/stripe-test-plan"))
    parser.add_argument("--test-user-id", action="append", default=[])
    parser.add_argument("--check-stripe", action="store_true", help="read-only Stripe test API checks")
    args = parser.parse_args()
    try:
        prepare(args.output_dir, args.test_user_id, args.check_stripe)
        return 0
    except AuditError as error:
        print(str(error), file=sys.stderr)
    except Exception:
        print("Read-only audit failed; exception details suppressed to protect credentials.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
