# Produktions-Healthcheck: fehlende IAM-Leserechte

## Nachgewiesene Ursache, 18.10.2026

Im [Lauf 39124483685](https://github.com/stefanbeike/gratilo/actions/runs/39124483685)
von Commit `6a38ee1484b94fdad5bb7ae9c483f7d20793f1cd` wurden CI, Docker-Build,
Trivy und Publish erfolgreich abgeschlossen. **v0.0.3** liegt vollständig in
ECR; die drei Images wurden als Digests in Task-Revision 2 gestartet.
Die Stabilitätsprüfung bestätigte den erfolgreichen ECS-Rollout. Anschließend
fehlte der GitHub-Rolle die Berechtigung für `ecs:ListTasks`. Das ursprüngliche
Healthcheck-Skript unterdrückte diese konkrete Ursache hinter einem Sammelfehler.

Die tatsächliche Inline-Policy `gratilo-github-ecs-deploy` enthielt nur
DescribeTaskDefinition/DescribeServices, RegisterTaskDefinition, UpdateService
und PassRole. **ListTasks, DescribeTasks und DescribeTargetHealth fehlten
vollständig.** Die Repository-Vorlage war bereits umfangreicher, aber nicht
in AWS übernommen. `Resource: "*"` mit Cluster-Bedingung in der bisherigen
Vorlage war nicht die Ursache des beobachteten Fehlers.

Die Rolle hat keine Managed Policies und keine Permissions Boundary; neben
der ECS-Policy existiert die unveränderte Inline-Policy `gratilo-github-ecr-push`.
Organizations-Abfragen zeigen keine SCPs am Account/Root und keine aktivierten
Policy-Typen am Root. Der Actions-Aufruf übergibt keine zusätzliche Session-Policy.

## Aktueller Produktionszustand

Der automatische Rollback hat **gratilo-prd-app:1 / v0.0.1** wiederhergestellt.
Die Pipeline konnte den Rollback wegen derselben fehlenden Diagnoseberechtigung
nicht verifizieren und endete korrekt als fehlgeschlagen. Die anschließende
rein lesende Prüfung mit Profil `gratilo` bestätigt:

- Service ACTIVE, eine abgeschlossene Deployment-Revision, **Running/Desired 1/1**,
  Pending 0.
- Task und alle drei Container RUNNING/HEALTHY; Frontend-ALB-Target healthy.
- `https://gratilo.eu/` liefert 200 mit TLS-Prüfung.
- `/user-service/features` liefert die erwartete deaktivierte Pool-Funktion.
- `/payment-service/transactions` weist den anonymen GET wie vorgesehen ab.

Es wurde kein weiterer ECS-Rollout gestartet, keine Task-Definition registriert,
kein Image gebaut oder gepusht, kein Git-Tag erstellt und keine Datenbank geändert.

## Minimale IAM-Korrektur

Die vorhandene AWS-Inline-Policy wurde nach ausdrücklicher Freigabe um genau
drei Lesestatements aus der korrigierten
[Repository-Vorlage](../deployment/iam/github-ecs-deployment-policy.json) ergänzt.
Alle vorherigen Statements, insbesondere Schreibrechte und PassRole, blieben
unverändert. Die Vorlage ist das vollständige Sollmodell; die AWS-Änderung war
bewusst nur die Ergänzung der fehlenden Healthcheck-Rechte.

| Aktion | Resource / Begrenzung |
|---|---|
| `ecs:DescribeServices` | Bestehend erlaubt; Sollvorlage beschränkt auf `service/gratilo-prd-cluster/gratilo-prd-app` |
| `ecs:ListTasks` | `arn:aws:ecs:eu-central-1:070351676632:container-instance/gratilo-prd-cluster/*`, zusätzlich `ArnEquals ecs:cluster` auf den exakten Cluster-ARN |
| `ecs:DescribeTasks` | `arn:aws:ecs:eu-central-1:070351676632:task/gratilo-prd-cluster/*` |
| `elasticloadbalancing:DescribeTargetHealth` | `*`, ausschließlich diese Leseaktion |

Auch bei Fargate autorisiert AWS ListTasks gegen den Ressourcentyp
**container-instance**, nicht task oder cluster. `ecs:cluster` ist ein
unterstützter ARN-Condition-Key. Die explizite Ressourcengrenze vermeidet eine
unnötig globale Freigabe. DescribeTargetHealth unterstützt keine
Ressourcenbeschränkung auf einen Target-Group-ARN. Die vollständige Sollvorlage
trennt außerdem DescribeTaskDefinition in ein eigenes Read-Statement mit `*`,
weil diese Aktion keine ressourcenbezogene Einschränkung unterstützt.

Quellen: [AWS ECS Service Authorization](https://docs.aws.amazon.com/service-authorization/latest/reference/list_ecs.html),
[AWS ELBv2 Service Authorization](https://docs.aws.amazon.com/service-authorization/latest/reference/list_elbv2.html).

Die Änderung wurde aus AWS zurückgelesen. Acht anschließende Simulationen der
tatsächlichen GitHub-Rolle erlauben alle vier benötigten Leseaktionen und
verweigern ListTasks für fremde Ressourcen, fremden/fehlenden Cluster-Kontext
sowie DescribeTasks für einen fremden Cluster. Backup, angewendeter Diff und
Simulationsergebnisse liegen lokal unter `/tmp/gratilo-health-fix/`; sie
enthalten nur IAM-Policy-Metadaten, keine Zugangsdaten.

Die IAM-Simulation ist kein neuer OIDC-Login der GitHub-Rolle. Der Live-Healthcheck
wurde mit dem lokalen Profil `gratilo` ausgeführt; der nächste Actions-Lauf
bestätigt die Berechtigungen zusätzlich in der tatsächlichen OIDC-Session.

## Verbesserte Diagnose und Rollback

`verify_ecs_health.py` unterscheidet jetzt `IAM_ACCESS_DENIED` mit konkretem
Aktionsnamen, `AWS_AUTHENTICATION`, `AWS_API_ERROR`, `ECS_SERVICE_STATE`,
`ECS_TASK_STATE`, `CONTAINER_UNHEALTHY`, `ALB_CONFIGURATION`, `ALB_UNHEALTHY`,
`HTTPS_TLS`, `HTTPS_NETWORK`, `HTTPS_STATUS` und `BACKEND_RESPONSE`.
AWS-/HTTP-Antwortinhalte, rohe Fehlermeldungen, Tokens und Zugangsdaten werden
nicht ausgegeben. TLS- und Hostnamenprüfung bleiben aktiv; Weiterleitungen
gelten nicht als erfolgreicher HTTPS-Healthcheck.

Vor der ersten Ressourcenänderung führt `deploy_ecs.sh` einen rein lesenden
`--permissions-only`-Check aus. Fehlende Diagnoseberechtigungen stoppen damit
bereits vor RegisterTaskDefinition/UpdateService. Der Check verlangt nicht,
dass eine bereits gestörte Anwendung gesund ist, damit ein Reparaturdeployment
möglich bleibt. Ohne vorhandene laufende Tasks kann DescribeTasks erst nach
dem Deployment konkret geprüft werden; das wird ausdrücklich gemeldet.

Ein später fehlgeschlagener Healthcheck löst weiterhin den Rollback auf die
vorherige Revision aus. Ein stabilisierter Service wird vor der abschließenden
Rollback-Healthprüfung ausdrücklich gemeldet. Schlägt diese Prüfung etwa wegen
IAM fehl, bleibt `ROLLBACK NOT VERIFIED` sichtbar. Selbst ein vollständig
verifizierter Rollback beendet das ursprüngliche Deployment mit Fehlerstatus.
Fremde, zwischenzeitlich ausgerollte Revisionen werden nicht überschrieben.

## Sicherer Wiederanlauf ohne neue Images oder Tags

1. GitHub → Actions → **Gratilo Release & Deploy Production** →
   [fehlgeschlagener Lauf 39124483685](https://github.com/stefanbeike/gratilo/actions/runs/39124483685).
2. **Re-run jobs → Re-run failed jobs** wählen, nicht **Re-run all jobs** und
   keinen neuen Gesamt-Release starten.
3. Falls GitHub erneut fragt: **Review deployments → production → Approve**.
   Selbstfreigabe durch `stefanbeike` ist erlaubt.
4. Nur Deploy wird erneut ausgeführt. Erfolgreiche CI-/Build-/Publish-Jobs und
   ihre Digest-Outputs bleiben erhalten. Es werden keine Images neu gebaut und
   keine vorhandenen Git-/ECR-Tags überschrieben. Die bestehenden v0.0.3-Digests
   werden in einer neuen ECS-Revision verwendet; Stabilität und Healthchecks
   müssen anschließend erfolgreich sein.

Ein Retry verwendet den alten Workflow-Commit `6a38ee1`. Das ist hier ausreichend,
weil die tatsächlich fehlenden IAM-Rechte bereits in AWS korrigiert wurden und
der alte Healthcheck dieselben vier APIs verwendet. Die neu ergänzte Vorabprüfung
und die besseren Fehlermeldungen aus main gelten erst für zukünftige Läufe,
deren Commit diese Codekorrektur enthält. Kein neuer Release-Tag ist zur
Wiederaufnahme des vorhandenen Deployments notwendig.

## Validierung

- 96 Python-Regressionstests und 7 OIDC-Tests erfolgreich.
- Actionlint, `bash -n deployment/scripts/deploy_ecs.sh` und `git diff --check`
  erfolgreich.
- Tests decken fehlende IAM-Rechte vor jeder Mutation, spätere IAM-/Container-/
  ALB-/TLS-Fehler mit Rollback, weiterhin fehlschlagende Diagnose nach stabilisiertem
  Rollback, unveränderten Fehlerstatus und Schutz fremder Revisionen ab.
- IAM-Fehlermeldungen, TLS-/Netzwerkfehler und ungültige Antworten werden getrennt
  und ohne Test-Secret-Marker ausgegeben. Ein ungesunder Ausgangszustand ist
  kein Grund, die reine Berechtigungsprüfung zu blockieren.
- Der neue vollständige Healthcheck gegen die laufende Revision 1 ist erfolgreich.
