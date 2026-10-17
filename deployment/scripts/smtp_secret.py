"""Audit or explicitly update SES credentials without printing their values.

Inputs stay in memory; AWS writes use stdin, never arguments or temporary files.
SMTP checks use verified STARTTLS and AUTH only. No message is sent.
"""
import argparse
import base64
import getpass
import hashlib
import hmac
import json
import re
import smtplib
import ssl
import subprocess
import sys
import uuid

ACCOUNT = "070351676632"
REGION = "eu-central-1"
SECRET = "gratilo/prd/smtp"
CONFIRMATION = "UPDATE gratilo/prd/smtp"


def smtp_password_format(value):
    if not isinstance(value, str) or value != value.strip():
        return False
    try:
        decoded = base64.b64decode(value, validate=True)
        return len(decoded) == 33 and decoded[0] == 4
    except (ValueError, TypeError):
        return False


def valid_username(value):
    return isinstance(value, str) and re.fullmatch(r"AKIA[A-Z0-9]{16}", value) is not None


def derive_smtp_password(raw_secret):
    if not re.fullmatch(r"[A-Za-z0-9/+=]{40}", raw_secret):
        raise ValueError("Expected a long-term AWS secret access key, not an SMTP password")
    key = ("AWS4" + raw_secret).encode()
    for message in ("11111111", REGION, "ses", "aws4_request", "SendRawEmail"):
        key = hmac.new(key, message.encode(), hashlib.sha256).digest()
    return base64.b64encode(b"\x04" + key).decode()


def aws(profile, service, operation, *arguments, payload=None):
    command = ["aws", service, operation, *arguments, "--profile", profile,
               "--region", REGION, "--output", "json", "--no-cli-pager",
               "--cli-connect-timeout", "10", "--cli-read-timeout", "20"]
    if payload is not None:
        command += ["--cli-input-json", "file:///dev/stdin"]
    result = subprocess.run(command, input=json.dumps(payload) if payload is not None else None,
                            capture_output=True, text=True)
    if result.returncode:
        # stderr may contain SecretString. Only allow an AWS error code through.
        match = re.search(r"An error occurred \(([A-Za-z0-9]+)\)", result.stderr)
        code = match.group(1) if match else "CLI_OR_NETWORK_ERROR"
        raise RuntimeError(f"AWS {service}:{operation} failed ({code}); no payload printed")
    try:
        return json.loads(result.stdout)
    except (ValueError, TypeError):
        raise RuntimeError("AWS returned an unreadable response; no payload printed") from None


def read_secret(profile):
    document = aws(profile, "secretsmanager", "get-secret-value", "--secret-id", SECRET)
    try:
        values = json.loads(document["SecretString"])
        if not isinstance(values, dict) or not all(isinstance(values.get(k), str) for k in ("username", "password")):
            raise ValueError()
        return document["VersionId"], values
    except (ValueError, TypeError, KeyError):
        raise RuntimeError("SMTP secret must contain JSON string fields username and password") from None


def authenticate(username, password):
    try:
        with smtplib.SMTP("email-smtp.eu-central-1.amazonaws.com", 587, timeout=15) as smtp:
            smtp.set_debuglevel(0)
            smtp.ehlo()
            smtp.starttls(context=ssl.create_default_context())
            smtp.ehlo()
            code, _ = smtp.login(username, password)
            return code
    except smtplib.SMTPAuthenticationError as failure:
        return failure.smtp_code
    except (OSError, UnicodeError, smtplib.SMTPException):
        raise RuntimeError("SMTP TLS/AUTH check failed; no provider response printed") from None


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["audit", "update"])
    parser.add_argument("--profile", default="gratilo")
    parser.add_argument("--check-auth", action="store_true", help="AUTH only; never sends mail")
    parser.add_argument("--keep-password", action="store_true", help="Only replace the username after successful AUTH")
    parser.add_argument("--derive", action="store_true", help="Derive the Frankfurt SMTP password from a hidden AWS secret access key")
    parser.add_argument("--confirm-write", default="")
    args = parser.parse_args(arguments)
    if args.action == "update" and args.confirm_write != CONFIRMATION:
        parser.error("Production update requires --confirm-write 'UPDATE gratilo/prd/smtp'")
    if args.action == "audit" and (args.keep_password or args.derive or args.confirm_write):
        parser.error("Write options cannot be used with audit")
    if args.keep_password and args.derive:
        parser.error("Choose either --keep-password or --derive")
    try:
        if args.action == "update" and not sys.stdin.isatty():
            raise RuntimeError("Credential update requires an interactive terminal with hidden input")
        if aws(args.profile, "sts", "get-caller-identity").get("Account") != ACCOUNT:
            raise RuntimeError("Wrong AWS account; stopped before reading or writing the secret")
        version, current = read_secret(args.profile)
        if args.action == "audit":
            result = {"username_is_long_term_access_key_id": valid_username(current["username"]),
                      "password_has_ses_smtp_v4_format": smtp_password_format(current["password"])}
            if args.check_auth:
                result["smtp_auth_code"] = authenticate(current["username"], current["password"])
            print(json.dumps(result, indent=2))
            return 0
        username = getpass.getpass("SES SMTP username / access-key ID (hidden): ")
        if not valid_username(username):
            raise ValueError("SMTP username must be a long-term access-key ID, not an IAM username or temporary key")
        if args.keep_password:
            password = current["password"]
        else:
            password = getpass.getpass("AWS secret access key (hidden): " if args.derive else "SES SMTP password (hidden): ")
            if args.derive:
                password = derive_smtp_password(password)
        if not smtp_password_format(password):
            raise ValueError("Password is not in SES SMTP v4 format; no secret change made")
        if authenticate(username, password) != 235:
            raise RuntimeError("SMTP rejected candidate credentials; no secret change made")
        if read_secret(args.profile)[0] != version:
            raise RuntimeError("SMTP secret changed during validation; no secret change made")
        if current["username"] == username and current["password"] == password:
            print("Credentials already match and AUTH succeeded; secret unchanged. No email sent.")
            return 0
        updated = {**current, "username": username, "password": password}
        aws(args.profile, "secretsmanager", "put-secret-value", payload={
            "SecretId": SECRET, "ClientRequestToken": str(uuid.uuid4()), "SecretString": json.dumps(updated)})
        print("SMTP secret updated after successful AUTH. Replace the ECS task to load it. No email sent.")
        return 0
    except RuntimeError as error:
        print(str(error))  # Only fixed messages above, never raw CLI/provider errors.
        return 1
    except ValueError:
        print("Credential format invalid; no secret change made and no values printed")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
