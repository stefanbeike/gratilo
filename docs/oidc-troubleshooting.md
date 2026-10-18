# OIDC-Fehler beim ECR-Release

## Nachgewiesene Ursache am 16.10.2026

Der fehlgeschlagene manuelle main-Lauf `38901743182` verwendet Commit
`69745d49da4c40bed17984c6a8d4995d2d897657`. Dessen aufgerufener Publish-Job in
`007_deploy_ecs.yml` hat korrekt `environment: production` und `id-token: write`.
Auch der aufrufende Job in `release.yml` erlaubt `id-token: write`.
Die Environment-Variable verweist auf die erwartete Rolle
`arn:aws:iam::070351676632:role/gratilo-github-actions-role`.
Es gibt keinen abweichenden Environment-Namen im Workflow.

Die ausschließlich lesende GitHub-Abfrage
`GET /repos/stefanbeike/gratilo/actions/oidc/customization/sub` ergibt:

```json
{
  "use_default": true,
  "use_immutable_subject": true,
  "sub_claim_prefix": "repo:stefanbeike@13099702/gratilo@1351208624"
}
```

Repository-ID `1351208624` und Owner-ID `13099702` wurden über die Repository-
Metadaten bestätigt; das Repository wurde am 30.08.2026 erstellt. GitHub verwendet
für solche Repositories standardmäßig unveränderliche Subjects mit IDs.
[GitHub-Dokumentation](https://docs.github.com/en/actions/reference/security/oidc#immutable-subject-claims).

Damit lautet der Subject für dieses Environment:

```text
repo:stefanbeike@13099702/gratilo@1351208624:environment:production
```

Die vom Benutzer angegebene AWS-Trust-Policy erwartet dagegen
`repo:stefanbeike/gratilo:environment:production`, ohne IDs. Ein IAM-StringEquals
auf diesen alten Wert passt nicht. Auch die bisherigen lokalen Policy-Vorlagen
enthielten dieses alte Format und sind jetzt korrigiert. Ein Wechsel der AWS-
Action-Version oder zusätzliche ECR-Berechtigungen behebt diesen Subject-Fehler
nicht. `job_workflow_ref` ist ein separater Claim; bei der bestätigten Default-
Konfiguration fügt der wiederverwendbare Workflow keinen zusätzlichen Subject-
Bestandteil hinzu.

## Erforderliche manuelle AWS-Korrektur

In der AWS-Konsole die Vertrauensrichtlinie der Rolle
`gratilo-github-actions-role` prüfen und den StringEquals-Subject auf den obigen
vollständigen Wert mit IDs setzen. Audience bleibt `sts.amazonaws.com`.
Die vollständige Vorlage steht in
`deployment/iam/github-oidc-production-trust.json` (identisch mit der allgemeinen
OIDC-Trust-Vorlage). Der Federated Principal verweist weiterhin auf
`arn:aws:iam::070351676632:oidc-provider/token.actions.githubusercontent.com`.
Keine Wildcards oder Erweiterung auf alle Branches verwenden.

Die separate `github-oidc-main-trust.json` ist nur für Jobs ohne Environment
vorgesehen und enthält jetzt ebenfalls die unveränderlichen IDs. Der aktuelle
Publish-Job benötigt die production-Variante. Für den aktuellen Tag-Release im
GitHub Environment main und eine separate Tag-Regel `v*` erlauben; Required
reviewers beibehalten. Der manuelle Produktionsdeploy ist im Workflow zusätzlich
auf main beschränkt. Siehe [aktuelle CI/CD-Anleitung](cicd.md).

Es wurden keine AWS-Einstellungen gelesen oder geändert, kein Workflow gestartet,
kein OIDC-Token angefordert und keine Images veröffentlicht. Die tatsächlichen
Token-Claims eines künftigen Laufs bestätigt die Diagnose nach manueller Freigabe.

## Sichere Diagnose im vorhandenen Publish-Job

Direkt vor `configure-aws-credentials@v4` läuft künftig
`node deployment/scripts/oidc_claims.cjs`. Beide Schritte fordern ausdrücklich
Audience `sts.amazonaws.com` an. Die Diagnose fordert einen Token nur über den
GitHub-Runner-Kontext an und gibt ausschließlich diese fünf Claims aus:

- `sub`
- `aud`
- `repository`
- `ref`
- `environment`

Der vollständige Token und der Request-Bearer werden weder protokolliert noch
als Output/Umgebungsvariable exportiert oder gespeichert. Fehler unterdrücken
Response-Body, URLs und Exception-Details. Zusätzliche Payload-Felder werden
verworfen; ungültige Claim-Typen werden nicht ausgegeben. Die fünf Werte müssen
der bestätigten Produktionsidentität entsprechen, sonst endet die Diagnose vor
STS/ECR mit Fehler. Das Dekodieren dient ausschließlich der Diagnose; die
kryptografische Token-Prüfung erfolgt weiterhin bei AWS STS.

Lokale Tests verwenden ausschließlich synthetische, nicht verwendbare Token-
Fixtures und einen gemockten HTTP-Client. Die CI führt sie mit `node --test` aus.
Es gibt keinen neuen Workflow und keine zusätzliche Berechtigung.
