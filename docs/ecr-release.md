# Gratilo ECR-Release ohne Stripe

Die aktuelle Anleitung steht unter [CI/CD: Tag-Release und manuelles Deployment](cicd.md).

Ein stabiler SemVer-Tag-Push startet jetzt Tests, einmalige Docker-Builds und
Trivy-Scans. Nach der bestehenden production-Freigabe werden die geprüften Images
nach ECR veröffentlicht. Der Release deployt niemals ECS.

Der bisherige manuelle Release mit version_bump und PUBLISH gratilo-sandbox wurde
abgelöst. Git-Tags werden vom Betreiber angelegt; der Publisher benötigt nur
contents: read. Für ECS gibt es ausschließlich den separaten manuellen Workflow
deploy-production.yml. GitHub-Einstellungen, IAM, Konflikte und Rollback sind in
der verlinkten Anleitung vollständig beschrieben.
