# Gratilo: HTTPS und kanonische Domain

Stand: 17.10.2026. Der Betreiber hat die beschriebenen Änderungen anschließend
mit „ja mache das“ ausdrücklich freigegeben. Der erste Änderungsversuch scheiterte
am Netzwerkzugriff dieser Sitzung; **es wurde keine produktive Änderung wirksam**.
Die Freigabe für die beschriebenen Maßnahmen liegt weiterhin vor.

## Nachgewiesener Ist-Zustand

- `https://gratilo.eu`: curl HTTP 200, `ssl_verify_result=0`.
- Der HTTPS-Listener von `gratilo-prd-alb` verwendet das ACM-Zertifikat
  `arn:aws:acm:eu-central-1:070351676632:certificate/e1e1421b-c423-4c6b-a5d6-074f8b17f05b`.
  ACM meldet `ISSUED`, SANs `gratilo.eu` und `www.gratilo.eu`, Ablauf
  01.05.2027, 01:59:59 Europe/Berlin. Ein neues Zertifikat ist nach diesem
  Befund nicht erforderlich. Ein Redirect kann einen TLS-Fehler vor dem
  HTTP-Request nicht beheben.
- Der HTTPS-Listener hat nur die Standardregel zum Frontend-Target.
  Eine www-Weiterleitung fehlt.
- Der HTTP-Listener leitet mit 301 nach HTTPS weiter, behält aber mit
  `#{host}` den angefragten Host. Das erfüllt die kanonische Weiterleitung
  auf `gratilo.eu` nicht.
- In der öffentlichen Route-53-Zone `Z09477111FIYLITD4ZLRZ` existiert der
  A-Alias `gratilo.eu` zum ALB. Die erfolgreiche A/AAAA/NS-Abfrage enthält
  keinen A- oder AAAA-Eintrag für www und keine AAAA-Einträge für die Hauptdomain.
- Die zusätzliche CNAME-Abfrage und vollständige DNS-Abfrage scheiterten
  am eingeschränkten Netzwerkzugang dieser Sitzung. Deshalb ist ein möglicher
  www-CNAME noch nicht ausgeschlossen. Auch die www-TLS-Verbindung konnte
  wegen fehlender DNS-Auflösung in dieser Sitzung nicht untersucht werden.
  Selbst HTTP gratilo.eu war bei einer anderen curl-Abfrage von dieser
  Einschränkung betroffen. Das ist **kein Beleg für einen öffentlichen
  DNS-Ausfall**. Die genaue Ursache der gemeldeten Browser-Zertifikatswarnung
  ist damit noch nicht nachgewiesen; eine fehlende Zertifikats-SAN ist ausgeschlossen.

## Vor Änderungen verbleibende Prüfung

Diese Befehle sind lesend. Alle AWS-Befehle verwenden das Produktionsprofil
und Frankfurt. Ergebnisse vor einer Änderung erneut prüfen und als Rollback-
Grundlage aufbewahren; sie enthalten keine Anwendungs-Secrets.

```bash
aws route53 list-resource-record-sets --hosted-zone-id Z09477111FIYLITD4ZLRZ --profile gratilo --region eu-central-1
aws elbv2 describe-listeners --load-balancer-arn arn:aws:elasticloadbalancing:eu-central-1:070351676632:loadbalancer/app/gratilo-prd-alb/dde865703a3c421d --profile gratilo --region eu-central-1
aws elbv2 describe-rules --listener-arn arn:aws:elasticloadbalancing:eu-central-1:070351676632:listener/app/gratilo-prd-alb/dde865703a3c421d/03f1eb0ac46a0816 --profile gratilo --region eu-central-1
aws acm describe-certificate --certificate-arn arn:aws:acm:eu-central-1:070351676632:certificate/e1e1421b-c423-4c6b-a5d6-074f8b17f05b --profile gratilo --region eu-central-1
```

Zusätzlich aus einem Netz mit funktionierender DNS-Auflösung `dig www.gratilo.eu A`,
`dig www.gratilo.eu AAAA` und `dig www.gratilo.eu CNAME` sowie die untenstehenden
curl-Prüfungen ausführen. Bei einer Zertifikatswarnung Issuer/SANs des tatsächlich
erreichten Servers prüfen, beispielsweise mit:

```bash
openssl s_client -connect www.gratilo.eu:443 -servername www.gratilo.eu -verify_hostname www.gratilo.eu -verify_return_error </dev/null
```

Ein abweichendes DNS-Ziel, ein lokaler DNS-Cache oder ein anderer TLS-Endpunkt
darf erst nach dieser Prüfung als Ursache benannt werden. Keine bestehenden
CNAME-/AAAA-Einträge ungeprüft überschreiben. Den ALB-IP-Adresstyp vor einem
etwaigen AAAA-Alias prüfen; hier ist nur der vorhandene IPv4-Alias vorgesehen.

## Vorbereitete Änderung, ausschließlich nach Freigabe

Die versionierten AWS-CLI-JSON-Dateien in `deployment/alb/` beschreiben den
gewünschten Zustand der vorhandenen Ressourcen. Im Repository gibt es aktuell
keine aktive Terraform-/CloudFormation-Konfiguration für diesen ALB; deshalb
wurde kein zusätzliches IaC-System eingeführt.

Reihenfolge nach erfolgreicher Prüfung und Freigabe:

1. HTTPS-Regel anlegen, sofern weiterhin keine entsprechende Regel existiert
   und Priorität 10 frei ist. Existiert die Regel bereits, diese gezielt prüfen
   und gegebenenfalls mit `modify-rule` aktualisieren statt ein Duplikat anzulegen.
2. HTTP-Standardaktion auf das feste Ziel umstellen.
3. Fehlenden www-Alias anlegen. Bei bereits korrektem Alias/CNAME diesen Schritt
   überspringen. Bei anderem Ziel zuerst die konkrete DNS-Korrektur freigeben lassen.
4. Route-53-Änderung mit `get-change` bis `INSYNC` verfolgen, DNS-Caches berücksichtigen
   und anschließend alle URL-Varianten prüfen.

Vom Repository-Hauptverzeichnis aus, **erst nach ausdrücklicher Freigabe**:

```bash
aws elbv2 create-rule \
  --listener-arn arn:aws:elasticloadbalancing:eu-central-1:070351676632:listener/app/gratilo-prd-alb/dde865703a3c421d/03f1eb0ac46a0816 \
  --priority 10 \
  --conditions file://deployment/alb/www-host-condition.json \
  --actions file://deployment/alb/canonical-redirect-actions.json \
  --profile gratilo --region eu-central-1

aws elbv2 modify-listener \
  --listener-arn arn:aws:elasticloadbalancing:eu-central-1:070351676632:listener/app/gratilo-prd-alb/dde865703a3c421d/c8375bc7862cc959 \
  --default-actions file://deployment/alb/canonical-redirect-actions.json \
  --profile gratilo --region eu-central-1

aws route53 change-resource-record-sets \
  --hosted-zone-id Z09477111FIYLITD4ZLRZ \
  --change-batch file://deployment/alb/www-dns-create.json \
  --profile gratilo --region eu-central-1
```

DNS verwendet absichtlich `CREATE`, kein blindes `UPSERT`: bestehende Einträge
werden nicht überschrieben. Beim erneuten Ausführen zuvor Zustand prüfen.
Bei teilweisem Fehlschlag keine weiteren Änderungen automatisch ausführen.
Bestehendes Zertifikat, HTTPS-Standardaktion, Target Group und ECS-Service
werden nicht geändert. Keine neue kostenpflichtige Infrastruktur erforderlich.

## Funktionsprüfung nach Freigabe und Umsetzung

Alle Aufrufe sind GET, erzeugen keine Benutzer, E-Mails oder Zahlungen und
prüfen TLS ohne `-k`. Der erste Durchlauf zeigt die erste Antwort; der zweite
prüft Weiterleitung und Schleifenschutz.

```bash
for url in https://gratilo.eu https://www.gratilo.eu http://gratilo.eu http://www.gratilo.eu; do
  curl --silent --show-error --max-time 20 --output /dev/null --dump-header - "$url"
  curl --silent --show-error --fail --location --max-redirs 2 --max-time 30 \
    --output /dev/null --write-out 'status=%{http_code} final=%{url_effective} redirects=%{num_redirects} tls=%{ssl_verify_result}\n' "$url"
done
curl --silent --show-error --max-time 20 --output /dev/null --dump-header - 'https://www.gratilo.eu/register?source=www-check'
curl --silent --show-error --fail --max-time 20 https://gratilo.eu/user-service/features
curl --silent --show-error --max-time 20 --output /dev/null --dump-header - https://gratilo.eu/payment-service/transactions
```

Erwartet: HTTPS-Hauptdomain direkt 200 ohne Redirect, die drei übrigen Varianten
jeweils 301 nach `https://gratilo.eu/` (optional explizites `:443`) und anschließend
200 mit genau einem Redirect und gültigem TLS. Register-Pfad und Query bleiben
in `Location` erhalten. Features antwortet 200 mit `tipPoolsEnabled:false`;
geschützte Payment-Transaktionen weisen einen nicht angemeldeten GET mit 401/403
ab, ohne Redirect zur Frontend-Startseite oder 502/504. Interne `/health`-Endpoints
bleiben intern auf 8081/8082; `/user-service/health` ist kein passender Proxy-Test.

Die HTTPS-Regel gilt nur für Host www. API-Requests auf der kanonischen
HTTPS-Domain laufen unverändert durch den bestehenden nginx-Proxy. Clients
sollten APIs direkt über `https://gratilo.eu` aufrufen: HTTP 301 garantiert nicht,
dass ein Client bei einem POST die Methode und den Body beibehält. Es werden
keine schreibenden API-Aufrufe über Weiterleitungen getestet.

Bei unerwartetem Ergebnis die zuvor gesicherten Listener-Aktionen wiederherstellen
und die neu angelegte Regel anhand der von `create-rule` gelieferten ARN gezielt
prüfen. Jegliches Entfernen von Regeln/DNS-Einträgen erfordert ebenfalls Freigabe.
Keine Zertifikate löschen.

AWS-Dokumentation: [ALB-Redirect-Aktionen, Platzhalter und Vermeidung von Schleifen](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/rule-action-types.html#redirect-actions).

## Lokale Validierung dieser Vorbereitung

- Alle drei JSON-Dateien erfolgreich geparst; festes HTTPS-Ziel, Erhaltung von
  Pfad/Query, ausschließliche www-Bedingung und DNS-`CREATE` geprüft.
- Eingaben für `CreateRule`, `ModifyListener` und `ChangeResourceRecordSets`
  offline gegen die installierten AWS-CLI-Service-Modelle geprüft:
  Feldnamen, erforderliche Felder, Typen, Enum-Werte und Wertebereiche gültig.
- Route-53-CLI-Skeleton-Prüfung erfolgreich. Die beiden ELB-Skeleton-Prüfungen
  scheiterten an ungültigen **generierten Ausgabe-Dummies** (`Order:0`, `Port:0`),
  nicht an den Eingabedateien. Deshalb zusätzliche direkte Modellprüfung.
  `--generate-cli-skeleton output` sendet keinen Änderungsaufruf an AWS.
- `git diff --check` erfolgreich. Keine Anwendungsdateien für diesen Fix geändert;
  bestehende uncommittete Registrierungsänderungen bleiben erhalten.
- HTTPS-Hauptdomain im erfolgreichen Live-curl: 200, TLS-Prüfung erfolgreich.
  Die drei weiteren URL-Varianten und ein zusätzlicher Backend-GET scheiterten
  bereits an der DNS-Auflösung im eingeschränkten Sitzungsnetz. Redirects,
  www-Zertifikatskette und API-Erreichbarkeit nach der Änderung sind damit noch
  **nicht** live bestätigt. Die obige Testfolge ist nach Umsetzung nachzuholen.

## Ausführungsversuch nach Freigabe

Die HTTPS-Regeln und Listener wurden unmittelbar vor dem Schreibversuch erneut
erfolgreich abgefragt: keine www-Regel vorhanden, Priorität 10 frei, HTTP-Redirect
noch mit `#{host}`. Die vollständige Route-53-Abfrage scheiterte weiterhin mit:

```text
Could not connect to the endpoint URL: "https://route53.amazonaws.com/2013-04-01/hostedzone/Z09477111FIYLITD4ZLRZ/rrset"
```

Der freigegebene `aws elbv2 create-rule`-Aufruf mit den versionierten JSON-Dateien
endete mit Exit-Code 255:

```text
Could not connect to the endpoint URL: "https://elasticloadbalancing.eu-central-1.amazonaws.com/"
```

Eine danach erfolgreiche erneute `describe-rules`-Abfrage bestätigt, dass weiterhin
nur die unveränderte HTTPS-Standardregel existiert. Die Regel wurde nicht angelegt.
Weitere Schreibaufrufe wurden deshalb nicht ausgeführt. Es wurden weder DNS noch
HTTP-Listener, Zertifikat, ECS-Service oder Anwendungsdaten geändert.

Erneuter curl gegen `https://gratilo.eu`: HTTP 200 und `ssl_verify_result=0`.
Der www-Aufruf scheiterte weiterhin an der lokalen DNS-Auflösung. Daraus lässt
sich kein erfolgreicher www-Fix ableiten.

Zur Fortsetzung muss diese Ausführungsumgebung Netzwerkzugriff auf die AWS-API-
Endpunkte und die öffentlichen Testdomains erlauben. Die Sitzung läuft mit
eingeschränktem Netzwerk und erlaubt keine Sandbox-Eskalation. Der Verbindungsfehler
belegt keinen IAM-Berechtigungsfehler; eine IAM-Erweiterung ist dadurch nicht
begründet. Nach Wiederherstellung des Zugriffs zunächst DNS/CNAME und freien
Regelplatz erneut prüfen, dann die bereits freigegebene Reihenfolge oben fortsetzen.

Auch der erneute, ausdrücklich angeforderte Ausführungsversuch scheiterte am
selben ELB-Verbindungsfehler (Exit-Code 255). Die unmittelbar zuvor erfolgreichen
Listener-/Regelabfragen zeigten weiterhin die ursprüngliche Konfiguration;
die erneute vollständige DNS-Abfrage scheiterte ebenfalls am Route-53-Endpunkt.
Die Freigabe ersetzt die technische Netzwerkbeschränkung dieser Sitzung nicht.
