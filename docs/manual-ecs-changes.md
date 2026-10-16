# Dateiänderungen des ECS-/Sandbox-Audits

Verglichen mit dem vorhandenen Feature-Commit `c02b5b7`. Frühere Änderungen
des Feature-Branches bleiben erhalten. Das vorhandene `aws/`-Hilfsskript wurde
nicht geändert.

- `.env.example`
- `.github/workflows/005_docker_build.yml`
- `.github/workflows/007_deploy_ecs.yml`
- `.github/workflows/release.yml`
- `README.md`
- `backend/payment-service/src/main/java/com/gratilo/payment/config/SandboxConfiguration.java`
- `backend/payment-service/src/main/java/com/gratilo/payment/controller/StripeWebhookController.java`
- `backend/payment-service/src/main/java/com/gratilo/payment/service/StripeCheckoutService.java`
- `backend/payment-service/src/main/java/com/gratilo/payment/service/StripeConnectService.java`
- `backend/payment-service/src/main/java/com/gratilo/payment/service/StripePayoutService.java`
- `backend/payment-service/src/main/resources/application-prd.yml`
- `backend/payment-service/src/main/resources/application.yml`
- `backend/payment-service/src/test/java/com/gratilo/payment/ProductionPaymentTest.java`
- `backend/payment-service/src/test/java/com/gratilo/payment/SandboxSecurityTest.java`
- `backend/user-service/src/main/java/com/gratilo/user/controller/EmailController.java`
- `backend/user-service/src/main/java/com/gratilo/user/controller/PoolDistributionController.java`
- `backend/user-service/src/main/java/com/gratilo/user/security/InternalSecret.java`
- `backend/user-service/src/main/java/com/gratilo/user/security/ProductionSecrets.java`
- `backend/user-service/src/main/java/com/gratilo/user/service/JwtService.java`
- `backend/user-service/src/main/resources/application.yml`
- `backend/user-service/src/test/java/com/gratilo/user/controller/InternalAuthenticationTest.java`
- `backend/user-service/src/test/java/com/gratilo/user/service/JwtSigningTest.java`
- `deployment/ecs/gratilo-prd-app.task-definition.json`
- `deployment/ecs/gratilo-task-definition.example.json`
- `deployment/ecs/user-service-ses.container.json`
- `deployment/iam/ecs-task-execution-policy.json`
- `deployment/iam/ecs-task-trust-policy.json`
- `deployment/iam/github-deployment-policy.json`
- `deployment/iam/github-oidc-main-trust.json`
- `deployment/iam/github-oidc-production-trust.json`
- `deployment/iam/github-oidc-trust-policy.json`
- `deployment/iam/gratilo-prd-execution-policy.json`
- `deployment/scripts/render_task_definition.py`
- `deployment/scripts/smoke_containers.py`
- `deployment/tests/test_ecs_deployment.py`
- `docker-compose/docker-compose.services.yml`
- `docs/amazon-ses.md`
- `docs/aws-ecs-deployment.md`
- `docs/manual-ecs-changes.md`
- `docs/manual-ecs-production.md`
- `frontend/Dockerfile`
- `frontend/nginx.conf`
