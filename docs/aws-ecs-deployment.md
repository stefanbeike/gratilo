# Gratilo AWS ECS: CI/CD und Laufzeitbetrieb

[CI/CD-Anleitung](cicd.md): automatische SemVer-Tag-Releases ohne automatischen
ECS-Deploy und separater, freigegebener manueller Produktionsdeploy. Enthält
GitHub-Variablen, Environment-Regeln, OIDC/IAM, Erstveröffentlichung, Healthchecks
und Rollback.

[Manuelle AWS-Laufzeiteinrichtung](manual-ecs-production.md): vorhandene Task,
Secrets Manager, Rollen, RDS, private Netzwerkverbindung, ALB und Container-Konfiguration.

Infrastruktur bleibt manuell verwaltet. Die freigegebene Deploy-Pipeline registriert
nur neue Revisionen der bestehenden Task-Familie und aktualisiert den bestehenden
Service. Terraform/Crossplane werden nicht ausgeführt. Zahlungen bleiben deaktiviert.
