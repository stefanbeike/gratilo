"""Wait for the selected revision's rollout state after services-stable returns."""
import argparse
import os
import sys
import time

from verify_ecs_health import HealthError, aws, validate_service


def wait(revision, timeout=180, interval=5):
    deadline = time.monotonic() + timeout
    announced = False
    while True:
        response = aws("ecs", "describe-services", "--cluster", os.environ["ECS_CLUSTER"],
                       "--services", os.environ["ECS_SERVICE"])
        services = response.get("services", [])
        if response.get("failures") or len(services) != 1:
            raise HealthError("ECS_SERVICE_STATE", "Expected one service without lookup failures.")
        service = services[0]
        if service.get("taskDefinition") != revision:
            raise HealthError("ECS_REVISION_CHANGED", "Service no longer selects the requested revision; a stable rollback is not a successful deployment.")
        if service.get("status") != "ACTIVE":
            raise HealthError("ECS_SERVICE_STATE", "Selected service is not active.")
        deployments = service.get("deployments", [])
        if any(d.get("taskDefinition") == revision and d.get("rolloutState") == "FAILED" for d in deployments):
            raise HealthError("ECS_ROLLOUT_FAILED", "Selected revision rollout failed.")
        try:
            validate_service(service, revision)
            print("Selected task revision rollout COMPLETED.")
            return
        except HealthError:
            pass  # Task counts may stabilize before rolloutState becomes COMPLETED.
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise HealthError("ECS_ROLLOUT_TIMEOUT", "Selected revision did not complete within the additional rollout window.")
        if not announced:
            print("Task counts stabilized; waiting for the selected revision rollout to become COMPLETED.", flush=True)
            announced = True
        time.sleep(min(interval, remaining))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("revision")
    args = parser.parse_args(argv)
    try:
        wait(args.revision)
    except HealthError as error:
        print(str(error), file=sys.stderr)
        return 1
    except (ValueError, KeyError, IndexError, TypeError, AttributeError, OSError):
        print("HEALTH_CONFIGURATION: rollout verification could not complete; response suppressed.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
