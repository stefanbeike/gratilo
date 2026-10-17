# Gratilo v0.0.1: produktives ECS-Deployment

Stand: 17.10.2026, Deployment abgeschlossen um 14:22:23 MESZ (12:22:23 UTC).
Alle AWS-CLI-Aufrufe verwendeten `--profile gratilo --region eu-central-1`.
Der Account wurde vor Änderungen mit STS als `070351676632` bestätigt.

## Ergebnis

| Prüfung | Ergebnis |
| --- | --- |
| ECS-Service `gratilo-prd-app` | `ACTIVE`, Deployment `COMPLETED`, steady state |
| Cluster | `gratilo-prd-cluster` |
| Task-Definition | `gratilo-prd-app:1`, vorhandene Revision weiterverwendet |
| Running / Desired / Pending | `1 / 1 / 0` |
| Task | `c3668088fd824474b6d70418105103c8` |
| Container | `frontend`, `user-service`, `payment-service`: `RUNNING`, `HEALTHY` |
| ALB-Target | `10.42.137.146:80`, `healthy` |
| HTTPS | `https://gratilo.eu`: HTTP 200, Zertifikatsprüfung erfolgreich |
| Zahlungen | `PAYMENTS_ENABLED=false`, Sandbox-Payouts und Demo deaktiviert |

## Durchgeführte AWS-Änderungen

1. Den zuvor nicht vorhandenen ECS-Service `gratilo-prd-app` erstellt:
   Fargate Linux/X86_64, Plattform 1.4.0, vorhandene Task-Definition mit
   1 vCPU / 3072 MiB, Desired Count 1, `assignPublicIp=DISABLED`.
   Deployment Circuit Breaker mit Rollback aktiviert, Health-Check-Grace-Period
   240 Sekunden, Minimum Healthy Percent 100, Maximum Percent 200.
   Den bestehenden ALB über die Frontend-Target-Group mit `frontend:80` verbunden.
2. In der vorhandenen öffentlichen Route-53-Zone
   `Z09477111FIYLITD4ZLRZ` den fehlenden A-Alias `gratilo.eu` angelegt.
   Ziel: `gratilo-prd-alb-1443598660.eu-central-1.elb.amazonaws.com`,
   ALB-Zonen-ID `Z215JYRZR1TBD5`, EvaluateTargetHealth `false`.
   Ausschließlich `CREATE`, keine vorhandenen Records überschrieben.
   Änderung `C0561664VHP49DI6SIPY` wurde `INSYNC`.
3. In ECS-Security-Group `sg-0cdb5b617a4dfd97d` die vorhandene TCP-80-Regel
   `sgr-096f90e121f7a6875` korrigiert: Quellgruppe war irrtümlich die
   RDS-Gruppe `sg-0b31d54acc7c91867`; sie ist jetzt ausschließlich die
   ALB-Gruppe `sg-0eb4dd906ce35350b`.
   Diese falsche Quelle verursachte zunächst ALB-Timeouts/HTTP 504 trotz
   gesunder Container. Es wurde kein öffentlicher ECS-Port freigegeben.

Keine Images gebaut/veröffentlicht, keine neue Task-Revision registriert,
keine IAM-Policies, Secrets, RDS-Konfiguration oder Listener geändert und
keine vorhandenen Ressourcen gelöscht. Kein neues NAT-Gateway oder
VPC-Endpoint erforderlich. Keine GitHub-Workflows gestartet.

## Verwendete Ressourcen und Konfiguration

| Ressource | Wert |
| --- | --- |
| VPC | `vpc-07825b07bcd00d0d1` |
| Private Task-Subnetze | `subnet-08a0a07b07ba145d7`, `subnet-0c25e23bc429c8e67` |
| Task-ENI | `eni-0ad70f54034ce6ef7`, private IP `10.42.137.146`, keine öffentliche IP |
| Vorhandenes NAT-Gateway | `nat-17a6b349ebc05b283`, verfügbar, aktive Default-Routen beider privater Subnetze |
| ALB | `gratilo-prd-alb`, internet-facing, IPv4, aktiv |
| Target Group | `gratilo-prd-frontend-tg`, Target Type `ip`, HTTP Port 80 |
| Target-Group-Health-Check | HTTP `/`, erwarteter Status 200, Intervall 30 s, Timeout 5 s, Healthy Threshold 5 |
| Execution Role | `gratilo-prd-ecs-execution-role` |
| Task Role | `gratilo-prd-ecs-task-role` |
| CloudWatch | `/ecs/gratilo-prd-app`, Aufbewahrung 30 Tage, Streams aller drei Container vorhanden |
| RDS | `gratilo-postgres-prd`, verfügbar, nicht öffentlich erreichbar |
| RDS-Endpoint | `gratilo-postgres-prd.chocqs4ummy7.eu-central-1.rds.amazonaws.com:5432` |
| Datenbank / Schemas | `gratilodb` / `user_mgmt`, `payment_mgmt` |

Beide Backend-JDBC-URLs verwenden `sslmode=verify-full` und das im Image
vorhandene `/app/rds-ca-bundle.pem`. Die RDS-Security-Group erlaubt TCP 5432
von der ECS-Security-Group. Die privaten Subnetze verwenden das bestehende
NAT für ECR, S3-Image-Layer, CloudWatch und Secrets Manager; zusätzliche
VPC-Endpoints sind für dieses vorhandene Routing nicht nötig.

Die Execution Role hat die vorhandene `AmazonECSTaskExecutionRolePolicy`
und `gratilo-prd-ecs-secrets-read` für die vier Produktionssecrets.
Die Secret-Referenzen der registrierten Revision wurden mit den
Secret-Metadaten abgeglichen; keine Secret-Werte ausgelesen:

| Secret | ECS-Umgebungsvariablen / JSON-Schlüssel |
| --- | --- |
| `gratilo/prd/database-3evgz9` | beide Backends: `DATASOURCES_DEFAULT_USERNAME` → `username`, `DATASOURCES_DEFAULT_PASSWORD` → `password` |
| `gratilo/prd/jwt-QtSUuJ` | beide Backends: `JWT_SECRET` → `secret` |
| `gratilo/prd/internal-api-Udyw4P` | beide Backends: `INTERNAL_API_KEY` → `secret` |
| `gratilo/prd/smtp-dViEp7` | User-Service: `JAVAMAIL_AUTHENTICATION_USERNAME` → `username`, `JAVAMAIL_AUTHENTICATION_PASSWORD` → `password` |

Die obigen Namen enthalten die ARN-Suffixe; die eigentlichen Secret-Namen
lauten `gratilo/prd/database`, `gratilo/prd/jwt`, `gratilo/prd/internal-api`
und `gratilo/prd/smtp`. Beide Backends verwenden dieselben JWT- und
Internal-API-Referenzen. Stripe-Secrets sind nicht eingebunden.

### Images

Alle drei ECR-Repositories sind `IMMUTABLE`. Der Laufzeit-Digest jedes
Containers stimmt mit dem vorhandenen `v0.0.1`-Image überein:

| Repository | Tag | Digest |
| --- | --- | --- |
| `gratilo/frontend` | `v0.0.1` | `sha256:b7c9ca5366759f5814cfaac03906b16022981b453a9fa71958b0e7dcbe50c24d` |
| `gratilo/user-service` | `v0.0.1` | `sha256:5364a85c848e72cba7b828abc9106e402fc0c08eb642423a172d01bca36f3305` |
| `gratilo/payment-service` | `v0.0.1` | `sha256:0c4bd8b373b0098b0f65b773caa708da866fea1c7f0dd56f073b090d2e38b02f` |

Registry: `070351676632.dkr.ecr.eu-central-1.amazonaws.com`.

### Routing und Health Checks

Der HTTPS-Listener 443 nutzt das gültige ACM-Zertifikat für `gratilo.eu`
und `www.gratilo.eu` und leitet alle Pfade an die Frontend-Target-Group.
Listener 80 leitet mit HTTP 301 auf HTTPS um. Zusätzliche ALB-Regeln für
die Backends sind nicht erforderlich:

- Frontend-Nginx leitet `/user-service/` auf `localhost:8081` und
  `/payment-service/` auf `localhost:8082` weiter.
- `/user-service/internal/` wird öffentlich mit HTTP 404 gesperrt.
- Die Backend-Health-Checks laufen innerhalb der Task gegen `/health`
  auf Port 8081 beziehungsweise 8082; Frontend-Health-Check gegen `/`.
- Das Frontend startet nach erfolgreichen Backend-Health-Checks.

## Tatsächlich ausgeführte Prüfungen

Die Produktions-HTTP-Prüfungen verwendeten ausschließlich GET, ohne
Login, Nutzerdatenänderung, Mailversand, Webhook-POST oder Zahlung.

| Prüfung | Ergebnis |
| --- | --- |
| `GET https://gratilo.eu/` | HTTP 200, React-Einstieg vorhanden, TLS-Verifikation erfolgreich |
| Beide im HTML referenzierten JS-/CSS-Assets | HTTP 200, echte Asset-Inhalte |
| `GET /user-service/features` | HTTP 200, `tipPoolsEnabled=false` |
| `GET /user-service/users/me` ohne Anmeldung | HTTP 401 |
| `GET /payment-service/transactions` ohne Anmeldung | HTTP 401 |
| `GET /user-service/internal/notifications/email` | HTTP 404, Nginx-Routensperre |
| `GET /payment-service/webhooks/stripe` | HTTP 503 mit `PAYMENTS_DISABLED`; kein Webhook ausgeführt |
| `GET http://gratilo.eu` | HTTP 301 auf HTTPS |
| Öffentliche DNS-Auflösung | A-Alias liefert die ALB-Adressen |
| ECS-/ALB-Waiter und abschließender Status | Service stabil, Deployment `COMPLETED`, Target `healthy` |

Die Startlogs wurden ausschließlich im Speicher ausgewertet. Die
Backend-Logs bestätigen erfolgreiche Liquibase-Updates, ohne beobachtete
Datenbankauthentifizierungs-, TLS- oder Migrationsfehler. Keine rohen
Anwendungslogs oder Secret-Werte wurden in diesen Bericht übernommen.
Die Anwendung führte ihre vorhandenen Liquibase-Migrationen beim Start
selbst aus; es wurden keine manuellen SQL-Änderungen vorgenommen.

## Offene Punkte und Prüfumfang

Keine offenen Infrastruktur-Deployment-Blocker bei Abschluss. Beim
Registrierungsversuch des Betreibers um 12:22:01 UTC meldete SES jedoch
SMTP `535 Authentication Credentials Invalid`; die Anwendung antwortete
darauf mit HTTP 500. SMTP-Host Frankfurt, Port 587 und Secret-Referenzen
sind korrekt. Die im Secret hinterlegten SMTP-Zugangsdaten müssen geprüft
und korrigiert werden. Während der Inbetriebnahme wurden keine Secret-Werte
ausgelesen und keine Mailversuche durch den Agenten ausgelöst. Die spätere,
separate [SMTP-Fehleranalyse](registration-smtp-fix.md) prüfte die Formate nur
im Arbeitsspeicher und führte AUTH ohne Mailversand aus.

Authentifizierte Nutzerabläufe und schreibende Geschäftsvorgänge wurden
nicht produktiv getestet. Stripe bleibt deaktiviert. Verfügbarkeit wurde
für den angeforderten einzelnen Task geprüft; kein Last- oder Ausfalltest.

Dieser Bericht ist eine Momentaufnahme des tatsächlichen Deployments,
keine Änderung an der automatischen Release-Pipeline. Repository-Änderungen
wurden nicht committed oder gepusht.
