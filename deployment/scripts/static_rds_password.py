"""Restore the existing application password; dry-run unless --apply is supplied.

Disabling RDS password management also deletes its RDS-managed secret.
The application secret is retained. No password is printed or written to disk.
"""
import argparse
import json
import os
import subprocess
import sys

ACCOUNT = "070351676632"
DATABASE = "gratilo-postgres-prd"
SECRET = "gratilo/prd/database"
VPC = "vpc-07825b07bcd00d0d1"


def aws(*args, payload=None):
    command = ["aws", "--profile", "gratilo", "--region", "eu-central-1",
               "--no-cli-pager", *args, "--output", "json"]
    if payload is None:
        result = subprocess.run(command, text=True, capture_output=True)
    else:
        # AWS CLI v2 does not reliably load JSON from /dev/stdin. A Linux memfd
        # gives its file loader a seekable input without persisting credentials.
        descriptor = os.memfd_create("gratilo-rds-request", flags=0)
        try:
            os.write(descriptor, json.dumps(payload).encode())
            os.lseek(descriptor, 0, os.SEEK_SET)
            command += ["--cli-input-json", f"file:///proc/self/fd/{descriptor}"]
            result = subprocess.run(command, pass_fds=(descriptor,), text=True, capture_output=True)
        finally:
            os.close(descriptor)
    if result.returncode:
        raise ValueError("AWS operation failed: " + " ".join(args[:2]) + "; response suppressed")
    return json.loads(result.stdout)


def validate_database(db):
    if (db.get("DBInstanceIdentifier") != DATABASE or db.get("Engine") != "postgres"
            or db.get("DBSubnetGroup", {}).get("VpcId") != VPC
            or db.get("PubliclyAccessible") is not False
            or db.get("DBInstanceStatus") != "available"
            or db.get("PendingModifiedValues")):
        raise ValueError("Unexpected database configuration or pending changes; aborting")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--acknowledge-managed-secret-deletion", action="store_true")
    args = parser.parse_args(argv)
    if args.apply and not args.acknowledge_managed_secret_deletion:
        parser.error("--apply requires --acknowledge-managed-secret-deletion")
    if aws("sts", "get-caller-identity")["Account"] != ACCOUNT:
        raise ValueError("Unexpected AWS account")
    db = aws("rds", "describe-db-instances", "--db-instance-identifier", DATABASE)["DBInstances"][0]
    validate_database(db)
    metadata = aws("secretsmanager", "describe-secret", "--secret-id", SECRET)
    if metadata.get("RotationEnabled") or metadata.get("DeletedDate"):
        raise ValueError("Application secret must be active with rotation disabled")
    print("Plan: use the existing gratilo/prd/database password as the static RDS master password.")
    print("RDS-managed secret to be deleted by AWS: " + db.get("MasterUserSecret", {}).get("SecretArn", "none"))
    if not args.apply:
        print("Dry-run only; no changes and no password values read.")
        return 0
    credentials = json.loads(aws("secretsmanager", "get-secret-value", "--secret-id", SECRET)["SecretString"])
    password = credentials.get("password")
    if credentials.get("username") != db.get("MasterUsername") or not isinstance(password, str) or not password:
        raise ValueError("Application secret must contain the master username and a nonempty password")
    aws("rds", "modify-db-instance", payload={"DBInstanceIdentifier": DATABASE,
        "ManageMasterUserPassword": False, "MasterUserPassword": password})
    print("Password change submitted. Verify RDS completion and ECS/ALB health separately.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, KeyError, IndexError):
        print("Static password operation failed; secret values and AWS response suppressed.", file=sys.stderr)
        sys.exit(1)
