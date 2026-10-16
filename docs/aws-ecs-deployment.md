# Gratilo AWS ECS: aktueller manueller Betrieb

Die verbindliche Anleitung, Variablenliste, Secrets-Manager-Zuordnung,
Task-Definition und manuellen Schritte stehen in
[manual-ecs-production.md](manual-ecs-production.md).

## GitHub Actions

Die bestehenden Workflows bauen und scannen alle drei Images und prüfen sie
mit PostgreSQL 15. Pull Requests erhalten keinen AWS-OIDC-Zugriff.

Der manuelle Workflow `Gratilo ECR Release (payments disabled)` kann nach erfolgreicher CI
und Environment-Freigabe geprüfte Images nach ECR veröffentlichen. Dafür:

- Repository Variable `ECR_PUBLISH_ENABLED=true` erst nach Einrichtung und Freigabe.
- Keine Stripe-Variable und kein Stripe-Secret nötig. Zahlungen bleiben deaktiviert.
- Environment `production`: ausschließlich main, Required reviewers.
- Environment Variables `AWS_ACCOUNT_ID=070351676632`,
  `AWS_REGION=eu-central-1`,
  `AWS_DEPLOY_ROLE_ARN=arn:aws:iam::070351676632:role/gratilo-github-actions-role`.
- Workflow ausschließlich manuell auf main starten; Bestätigung
  `PUBLISH gratilo-sandbox`. Dieser Lauf veröffentlicht Images, führt aber kein
  ECS-Deployment aus.

Der ECS-Deploy-Job wurde aus `007_deploy_ecs.yml` vollständig entfernt.
Task-Registrierung und Service-Änderungen erfolgen ausschließlich manuell in der
AWS-Konsole. Alte Terraform- und alternative ECR-Workflows sind aus dem aktiven
Workflow-Verzeichnis entfernt. Kein Terraform/Crossplane ausführen.

## OIDC und Berechtigungen

Da der Veröffentlichungsjob `environment: production` nutzt, muss der Trust-Subject
`repo:stefanbeike@13099702/gratilo@1351208624:environment:production` lauten. Der Branch-main-Subject
allein passt nicht; die Beschränkung auf main übernimmt zusätzlich das geschützte
GitHub Environment. Beide Policy-Varianten stehen unter `deployment/iam/`.

Die GitHub-Rolle braucht ausschließlich ECR-Veröffentlichungsrechte auf die drei
Repositories und ECR-Login einschließlich `ecr:DescribeRepositories` zur
Immutability-Prüfung. `github-deployment-policy.json` enthält diese Vorlage.
Nur der Publish-Job erhält zusätzlich GitHub `contents: write` für den Git-Tag
nach erfolgreichem Push aller Images. SemVer, Konflikte und Recovery beschreibt
[ecr-release.md](ecr-release.md).
Keine Anwendungs-Secrets für GitHub, keine permanenten AWS Access Keys,
keine ECS-/PassRole-Berechtigungen für den aktuellen manuellen Betriebsweg.

Die alten Deployment-Skripte und ihre Tests sind nur lokal mit simulierter AWS-CLI
geprüfte Hilfswerkzeuge; sie werden von keinem aktiven Deployment-Job ausgeführt.
Sie sind keine Freigabe, die Vorgabe zur manuellen AWS-Konsole zu umgehen.
