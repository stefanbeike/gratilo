# Gratilo AWS ECS: CI/CD und Laufzeitbetrieb

[CI/CD-Anleitung](cicd.md): genau ein manueller Workflow **Gratilo Release & Deploy
Production**, inklusive Versionierung, geprüfter ECR-Images, annotiertem Git-Tag
und unabhängiger Production-Freigabe vor ECS. Keine Push-/Tag-Deployments.

[Manuelle AWS-Laufzeiteinrichtung](manual-ecs-production.md): bestehende Task,
Secrets Manager, Rollen, RDS, private Netzwerke, ALB und Container-Konfiguration.
Infrastruktur bleibt manuell verwaltet. Die freigegebene Pipeline registriert
nur neue Revisionen der bestehenden Task-Familie und aktualisiert den bestehenden
Service. Kein Terraform/Crossplane. Zahlungen bleiben deaktiviert; Secrets unverändert.
