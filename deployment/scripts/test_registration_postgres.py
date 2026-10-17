"""Run real HTTP/transaction regression tests with disposable PostgreSQL 15.

Only loopback is published. EmailSendService is replaced by a mock in the
test context: no real emails, Stripe requests or AWS operations are performed.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]


def main():
    name = "gratilo-registration-test-" + uuid.uuid4().hex[:12]
    created = False
    try:
        subprocess.run([
            "docker", "run", "--detach", "--name", name,
            "--publish", "127.0.0.1::5432",
            "--env", "POSTGRES_DB=gratilo_registration_test",
            "--env", "POSTGRES_PASSWORD=local-registration-test-only",
            "postgres:15",
        ], check=True, stdout=subprocess.DEVNULL)
        created = True
        deadline = time.monotonic() + 60
        while True:
            ready = subprocess.run([
                "docker", "exec", name, "pg_isready", "-h", "127.0.0.1",
                "-U", "postgres", "-d", "gratilo_registration_test",
            ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if ready.returncode == 0:
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("Local PostgreSQL test startup timed out")
            time.sleep(1)
        subprocess.run([
            "docker", "exec", name, "psql", "-U", "postgres", "-d", "gratilo_registration_test",
            "--set", "ON_ERROR_STOP=1", "-c", "CREATE SCHEMA user_mgmt;",
        ], check=True, stdout=subprocess.DEVNULL)
        address = subprocess.check_output(["docker", "port", name, "5432/tcp"], text=True).strip()
        host, port = address.rsplit(":", 1)
        if host != "127.0.0.1" or not port.isdigit():
            raise RuntimeError("Test database must only be published on loopback")
        environment = {
            **os.environ,
            "REGISTRATION_TEST_DATABASE_URL": f"jdbc:postgresql://127.0.0.1:{port}/gratilo_registration_test",
            "REGISTRATION_TEST_DATABASE_DRIVER": "org.postgresql.Driver",
            "REGISTRATION_TEST_DATABASE_DIALECT": "POSTGRES",
            "REGISTRATION_TEST_DATABASE_USERNAME": "postgres",
            "REGISTRATION_TEST_DATABASE_PASSWORD": "local-registration-test-only",
            "REGISTRATION_TEST_SCHEMA_ACTION": "none",
            "REGISTRATION_TEST_LIQUIBASE": "true",
        }
        with tempfile.TemporaryDirectory(prefix="gratilo-registration-gradle-") as cache:
            print("Testing registration with local PostgreSQL 15, real Liquibase migrations and mocked SMTP.", flush=True)
            return subprocess.run([
                "bash", str(ROOT / "gradlew"), "--no-daemon", "--no-configuration-cache",
                "-Dorg.gradle.vfs.watch=false", "--project-cache-dir", cache,
                ":backend:user-service:registrationPostgresTest",
            ], cwd=ROOT, env=environment).returncode
    finally:
        if created:
            subprocess.run(["docker", "rm", "--force", "--volumes", name], check=True, stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    raise SystemExit(main())
