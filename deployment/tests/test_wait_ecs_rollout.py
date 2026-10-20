import contextlib
import copy
import io
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import wait_ecs_rollout as rollout


class RolloutWaitTest(unittest.TestCase):
    def setUp(self):
        self.service = {"status": "ACTIVE", "taskDefinition": "revision", "desiredCount": 1,
                        "runningCount": 1, "pendingCount": 0,
                        "deployments": [{"taskDefinition": "revision", "rolloutState": "COMPLETED"}]}
        self.env = patch.dict(os.environ, {"ECS_CLUSTER": "cluster", "ECS_SERVICE": "service"})
        self.env.start()
        self.addCleanup(self.env.stop)

    def response(self, **changes):
        return {"services": [{**copy.deepcopy(self.service), **changes}]}

    def test_stable_counts_with_in_progress_state_wait_until_completed(self):
        pending = self.response(deployments=[{"taskDefinition": "revision", "rolloutState": "IN_PROGRESS"}])
        with patch.object(rollout, "aws", side_effect=[pending, self.response()]) as aws, \
                patch.object(rollout.time, "sleep") as sleep, contextlib.redirect_stdout(io.StringIO()):
            rollout.wait("revision")
        self.assertEqual(2, aws.call_count)
        sleep.assert_called_once()

    def test_in_progress_is_bounded_and_never_reported_success(self):
        pending = self.response(deployments=[{"taskDefinition": "revision", "rolloutState": "IN_PROGRESS"}])
        with patch.object(rollout, "aws", return_value=pending), patch.object(rollout.time, "sleep") as sleep, \
                self.assertRaises(rollout.HealthError) as error:
            rollout.wait("revision", timeout=0)
        self.assertEqual("ECS_ROLLOUT_TIMEOUT", error.exception.category)
        sleep.assert_not_called()

    def test_rollback_and_foreign_revision_fail_immediately(self):
        for revision in ("old", "foreign"):
            with self.subTest(revision=revision), patch.object(rollout, "aws", return_value=self.response(taskDefinition=revision)), \
                    patch.object(rollout.time, "sleep") as sleep, self.assertRaises(rollout.HealthError) as error:
                rollout.wait("revision")
            self.assertEqual("ECS_REVISION_CHANGED", error.exception.category)
            sleep.assert_not_called()

    def test_failed_selected_deployment_fails_immediately(self):
        failed = self.response(deployments=[{"taskDefinition": "revision", "rolloutState": "FAILED"}])
        with patch.object(rollout, "aws", return_value=failed), self.assertRaises(rollout.HealthError) as error:
            rollout.wait("revision")
        self.assertEqual("ECS_ROLLOUT_FAILED", error.exception.category)

    def test_pending_tasks_or_extra_deployment_are_not_complete(self):
        for changes in ({"pendingCount": 1}, {"runningCount": 0}, {"deployments": self.service["deployments"] * 2}):
            with self.subTest(changes=changes), patch.object(rollout, "aws", return_value=self.response(**changes)), \
                    self.assertRaises(rollout.HealthError):
                rollout.wait("revision", timeout=0)

    def test_lookup_failure_is_not_mistaken_for_stability(self):
        with patch.object(rollout, "aws", return_value={"services": [], "failures": [{"reason": "MISSING"}]}), \
                self.assertRaises(rollout.HealthError) as error:
            rollout.wait("revision")
        self.assertEqual("ECS_SERVICE_STATE", error.exception.category)


if __name__ == "__main__":
    unittest.main()
