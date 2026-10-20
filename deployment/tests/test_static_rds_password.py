import contextlib
import copy
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("static_rds", Path(__file__).resolve().parents[1] / "scripts/static_rds_password.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class StaticRdsPasswordTest(unittest.TestCase):
    def setUp(self):
        self.db = {"DBInstanceIdentifier": module.DATABASE, "Engine": "postgres",
                   "DBSubnetGroup": {"VpcId": module.VPC}, "PubliclyAccessible": False,
                   "DBInstanceStatus": "available", "PendingModifiedValues": {},
                   "MasterUsername": "postgres", "MasterUserSecret": {"SecretArn": "managed-secret"}}

    def responses(self):
        return [{"Account": module.ACCOUNT}, {"DBInstances": [self.db]}, {"RotationEnabled": False}]

    def test_dry_run_does_not_read_password_or_mutate(self):
        with patch.object(module, "aws", side_effect=self.responses()) as aws, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, module.main([]))
        self.assertEqual(3, aws.call_count)
        self.assertEqual("describe-secret", aws.call_args.args[1])

    def test_apply_requires_explicit_deletion_acknowledgement_before_aws(self):
        with patch.object(module, "aws") as aws, contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            module.main(["--apply"])
        aws.assert_not_called()

    def test_rejects_foreign_account_before_database_queries(self):
        with patch.object(module, "aws", return_value={"Account": "123456789012"}) as aws, self.assertRaises(ValueError):
            module.main([])
        self.assertEqual(1, aws.call_count)

    def test_rejects_pending_or_public_or_foreign_database(self):
        for changes in ({"PubliclyAccessible": True}, {"PendingModifiedValues": {"EngineVersion": "16"}},
                        {"DBSubnetGroup": {"VpcId": "other"}}, {"DBInstanceStatus": "modifying"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                module.validate_database({**copy.deepcopy(self.db), **changes})

    def test_apply_changes_only_password_management_and_never_prints_password(self):
        responses = self.responses() + [{"SecretString": '{"username":"postgres","password":"fixture-only-password"}'}, {}]
        output = io.StringIO()
        with patch.object(module, "aws", side_effect=responses) as aws, contextlib.redirect_stdout(output):
            self.assertEqual(0, module.main(["--apply", "--acknowledge-managed-secret-deletion"]))
        self.assertEqual({"DBInstanceIdentifier": module.DATABASE, "ManageMasterUserPassword": False,
                          "MasterUserPassword": "fixture-only-password"}, aws.call_args.kwargs["payload"])
        self.assertNotIn("fixture-only-password", output.getvalue())


if __name__ == "__main__":
    unittest.main()
