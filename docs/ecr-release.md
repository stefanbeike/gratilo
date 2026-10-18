# Gratilo Release & Deploy Production

Der [einzige manuelle Gesamtworkflow](cicd.md) übernimmt Versionsplanung,
CI/Security, einmalige Docker-Builds, Trivy, ECR-Publish, annotierten Git-Tag
und nach unabhängiger Production-Freigabe das ECS-Deployment.

Start ausschließlich auf main mit version_bump und der exakten Bestätigung
RELEASE DEPLOY gratilo-production. Tag-Pushes starten keinen Release mehr.
Es gibt keinen separaten manuellen Publish- oder Deploy-Workflow.
Zahlungen bleiben deaktiviert. Einrichtung, Konflikte und Rollback: [cicd.md](cicd.md).
