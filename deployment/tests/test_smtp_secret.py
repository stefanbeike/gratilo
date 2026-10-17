import base64
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import smtplib
import subprocess
import unittest
from unittest.mock import patch, MagicMock

spec = importlib.util.spec_from_file_location("smtp_secret", Path(__file__).resolve().parents[1] / "scripts/smtp_secret.py")
smtp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smtp)
USERNAME = "AKIA" + "A" * 16  # Generated fixture, never a real access key.
PASSWORD = base64.b64encode(b"\x04" + b"x" * 32).decode()


class SmtpSecretTest(unittest.TestCase):
    def test_formats_distinguish_smtp_password_aws_secret_and_iam_username(self):
        self.assertTrue(smtp.valid_username(USERNAME))
        for value in ["iam-user-name", "ASIA" + "A" * 16, USERNAME + "\n", ""]:
            self.assertFalse(smtp.valid_username(value))
        self.assertTrue(smtp.smtp_password_format(PASSWORD))
        for value in ["a" * 40, "invalid", PASSWORD + "\n", None]:
            self.assertFalse(smtp.smtp_password_format(value))
        self.assertTrue(smtp.smtp_password_format(smtp.derive_smtp_password("a" * 40)))
        with self.assertRaises(ValueError):
            smtp.derive_smtp_password(PASSWORD)

    def test_write_payload_only_goes_to_stdin_and_cli_errors_never_leak_it(self):
        payload = {"SecretId": smtp.SECRET, "SecretString": "secret-payload-sentinel"}
        failed = subprocess.CompletedProcess([], 252, "", 'An error occurred (ParamValidation): secret-payload-sentinel')
        with patch.object(smtp.subprocess, "run", return_value=failed) as run:
            with self.assertRaises(RuntimeError) as failure:
                smtp.aws("gratilo", "secretsmanager", "put-secret-value", payload=payload)
            self.assertNotIn("secret-payload-sentinel", str(failure.exception))
            self.assertNotIn("secret-payload-sentinel", " ".join(run.call_args.args[0]))
            self.assertEqual(payload, json.loads(run.call_args.kwargs["input"]))
            self.assertIn("file:///dev/stdin", run.call_args.args[0])

    def test_update_requires_explicit_confirmation_before_any_aws_call(self):
        with patch.object(smtp, "aws") as aws, contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                smtp.main(["update"])
            aws.assert_not_called()

    def test_update_rejects_noninteractive_input_before_reading_credentials(self):
        with patch.object(smtp.sys.stdin, "isatty", return_value=False), patch.object(smtp, "aws") as aws:
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, smtp.main(["update", "--confirm-write", smtp.CONFIRMATION]))
            aws.assert_not_called()

    def test_audit_only_prints_format_flags_and_auth_status(self):
        with patch.object(smtp, "aws", return_value={"Account": smtp.ACCOUNT}) as aws, \
                patch.object(smtp, "read_secret", return_value=("version", {"username": USERNAME, "password": PASSWORD})), \
                patch.object(smtp, "authenticate", return_value=535), contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(0, smtp.main(["audit", "--check-auth"]))
            self.assertEqual({"username_is_long_term_access_key_id": True,
                              "password_has_ses_smtp_v4_format": True, "smtp_auth_code": 535}, json.loads(out.getvalue()))
            self.assertNotIn(USERNAME, out.getvalue())
            self.assertNotIn(PASSWORD, out.getvalue())
            self.assertFalse(any(c.args[2] == "put-secret-value" for c in aws.call_args_list))

    def run_update(self, auth_code=235, versions=("old", "old")):
        current = {"username": "wrong-iam-name", "password": PASSWORD, "preserve": "metadata"}
        with patch.object(smtp.sys.stdin, "isatty", return_value=True), \
                patch.object(smtp, "aws", return_value={"Account": smtp.ACCOUNT}) as aws, \
                patch.object(smtp, "read_secret", side_effect=[(v, current) for v in versions]), \
                patch.object(smtp.getpass, "getpass", return_value=USERNAME), \
                patch.object(smtp, "authenticate", return_value=auth_code), \
                contextlib.redirect_stdout(io.StringIO()) as out:
            status = smtp.main(["update", "--keep-password", "--confirm-write", smtp.CONFIRMATION])
            self.assertNotIn(USERNAME, out.getvalue())
            self.assertNotIn(PASSWORD, out.getvalue())
            writes = [c for c in aws.call_args_list if c.args[2] == "put-secret-value"]
            return status, writes

    def test_username_only_update_preserves_password_and_other_json_fields(self):
        status, writes = self.run_update()
        self.assertEqual(0, status)
        self.assertEqual(1, len(writes))
        self.assertEqual({"username": USERNAME, "password": PASSWORD, "preserve": "metadata"},
                         json.loads(writes[0].kwargs["payload"]["SecretString"]))

    def test_auth_failure_or_concurrent_secret_change_never_writes(self):
        for auth, versions in [(535, ("old",)), (235, ("old", "new"))]:
            with self.subTest(auth=auth, versions=versions):
                status, writes = self.run_update(auth, versions)
                self.assertEqual(1, status)
                self.assertEqual([], writes)

    def test_auth_uses_certificate_verified_starttls_and_never_sends_mail(self):
        with patch.object(smtp.smtplib, "SMTP") as factory:
            client = factory.return_value.__enter__.return_value
            client.login.return_value = (235, b"accepted")
            self.assertEqual(235, smtp.authenticate(USERNAME, PASSWORD))
            context = client.starttls.call_args.kwargs["context"]
            self.assertTrue(context.check_hostname)
            self.assertEqual(smtp.ssl.CERT_REQUIRED, context.verify_mode)
            client.set_debuglevel.assert_called_once_with(0)
            client.sendmail.assert_not_called()
            client.send_message.assert_not_called()
            self.assertEqual(2, client.ehlo.call_count)
            client.login.side_effect = smtplib.SMTPAuthenticationError(535, b"private-provider-response")
            self.assertEqual(535, smtp.authenticate(USERNAME, PASSWORD))


if __name__ == "__main__":
    unittest.main()
