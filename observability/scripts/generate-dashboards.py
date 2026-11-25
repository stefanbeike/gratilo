#!/usr/bin/env python3
"""Render versioned Grafana dashboards using the provisioned sources."""
import json
from pathlib import Path

DEST = Path(__file__).resolve().parents[1] / "grafana/dashboards"
PG = {"type": "grafana-postgresql-datasource", "uid": "gratilo-postgres"}
PROM = {"type": "prometheus", "uid": "gratilo-prometheus"}
LOKI = {"type": "loki", "uid": "gratilo-loki"}
EUR, COUNT, RATIO, SECONDS = "currencyEUR", "short", "percentunit", "s"
WINDOW = "to_timestamp(${__from}/1000.0), to_timestamp(${__to}/1000.0)"
ENV = "${environment:sqlstring} = gratilo_monitor.environment()"
MODE = "(${stripe_mode:sqlstring} = 'live')"
MODEL = "${kind:sqlstring}"
HTTP, OPS, DEP = "gratilo_http_server_requests_seconds", "gratilo_operation_duration_seconds", "gratilo_dependency_duration_seconds"
LABELS = 'environment=~"${environment:regex}"'
SERVICE = ',service=~"${service:regex}"'
NAMES = [
    ("executive", "Geschäftsüberblick"), ("payments", "Zahlungen und Stripe"),
    ("revenue", "Erlöse, Preise und Gebühren"), ("pools", "Pools und Teams"),
    ("growth", "Registrierung, Aktivierung und Wachstum"), ("payouts", "Auszahlungen und Stripe Connect"),
    ("backend", "Backend und API-Performance"), ("infrastructure", "Infrastruktur und AWS"),
    ("security", "Sicherheit und Audit"), ("operations", "Betrieb und Alarme"),
]


def custom(name, label, values, default, all_values=False):
    return {"name": name, "label": label, "type": "custom", "query": ",".join(values),
            "includeAll": all_values, "allValue": ".*" if all_values else None,
            "current": {"text": default, "value": "$__all" if all_values else default},
            "options": [{"text": v, "value": v, "selected": v == default} for v in values]}


class Board:
    def __init__(self, number, description, filters=""):
        slug, title = NAMES[number-1]
        self.x = self.y = self.height = 0
        self.panels = []
        self.service = "s" in filters
        self.model = {
            "uid": f"gratilo-{number:02d}-{slug}", "title": f"{number:02d} · {title}", "description": description,
            "tags": ["gratilo", "observability"], "schemaVersion": 41, "version": 1,
            "editable": False, "style": "dark", "timezone": "utc", "refresh": "1m",
            "time": {"from": "now-7d", "to": "now"},
            "timepicker": {"refresh_intervals": ["30s", "1m", "5m", "15m"]},
            "links": [{"title": "Gratilo-Dashboards", "type": "dashboards", "asDropdown": True,
                       "tags": ["gratilo"], "includeVars": True, "keepTime": True}],
            "templating": {"list": [
                {"name": "environment", "label": "Umgebung", "type": "query", "datasource": PROM,
                 "definition": 'label_values(up{job="prometheus"}, environment)',
                 "query": {"query": 'label_values(up{job="prometheus"}, environment)', "refId": "scope"},
                 "refresh": 1, "current": {"text": "demo", "value": "demo"}, "options": []},
                *[variable for key, variable in (
                    ("s", custom("service", "Dienst", ["user-service", "payment-service"], "All", True)),
                    ("k", custom("kind", "Zahlungsmodell", ["ALL", "SINGLE", "POOL"], "ALL")),
                    ("m", custom("stripe_mode", "Stripe-Modus", ["test", "live"], "test")),
                ) if key in filters],
            ]}, "panels": self.panels}

    def add(self, title, description, kind, width, height, **options):
        if self.x + width > 24:
            self.y += self.height
            self.x = self.height = 0
        self.panels.append({"id": len(self.panels)+1, "title": title, "description": description,
                            "type": kind, "gridPos": {"x": self.x, "y": self.y, "w": width, "h": height}, **options})
        self.x += width
        self.height = max(self.height, height)

    def row(self, title):
        self.add(title, "", "row", 24, 1, collapsed=False, panels=[])

    def note(self, content):
        self.add("Datenstand", "Grenzen der Messung.", "text", 24, 5, options={"mode": "markdown", "content": content})

    def panel(self, title, desc, ds, target, unit=COUNT, kind="stat"):
        width, height = {"stat": (6, 5), "timeseries": (12, 8), "barchart": (12, 8),
                         "table": (12, 8), "logs": (24, 9)}[kind]
        defaults = {"unit": unit, "noValue": "Unbekannt", "color": {"mode": "palette-classic"}}
        if kind == "stat":
            defaults.update(color={"mode": "thresholds"}, thresholds={"mode": "absolute",
                            "steps": [{"color": "blue", "value": None}]})
            if any(word in title for word in ("Kritische", "überfällig", "Fehlgeschlagene", "Ungültige")):
                defaults["thresholds"]["steps"] += [{"color": "orange", "value": 1}, {"color": "red", "value": 5}]
        if kind == "timeseries":
            defaults["custom"] = {"lineWidth": 2, "fillOpacity": 12, "showPoints": "never", "spanNulls": False}
        opt = {
            "stat": {"colorMode": "value", "graphMode": "none",
                     "reduceOptions": {"calcs": ["lastNotNull"], "values": False, "fields": "/^Wert$/" if ds == PG else ""}},
            "timeseries": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}},
            "barchart": {"xField": "Kategorie", "legend": {"showLegend": False}},
            "table": {"showHeader": True},
            "logs": {"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending"},
        }[kind]
        self.add(title, desc, kind, width, height, datasource=ds,
                 targets=[{"refId": "A", "datasource": ds, **target}],
                 fieldConfig={"defaults": defaults, "overrides": []}, options=opt)

    def sql(self, metric, title, desc, unit=COUNT, kind="stat", *, source="payments", model=MODEL):
        args = f"{WINDOW}, {MODE}" + (f", {model}" if source == "payments" else "")
        value = "value/100::numeric" if unit == EUR else "value"
        dimensions = 'dimension AS "Kategorie", ' if kind != "stat" else ""
        query = (f'SELECT {dimensions}{value} AS "Wert" FROM gratilo_monitor.{source}({args}) '
                 f"WHERE metric = '{metric}' AND {ENV} ORDER BY dimension")
        if source == "users":
            desc += " Nutzer und Pools gelten für die Umgebung. Der Stripe-Modus gilt für Connect."
        self.panel(title, desc, PG, {"rawSql": query, "rawQuery": True, "editorMode": "code", "format": "table"}, unit, kind)

    def batch(self, lines, **kwargs):
        for line in lines.strip().splitlines():
            metric, title, desc, *formatting = line.split("|")
            unit = {"eur": EUR, "ratio": RATIO, "s": SECONDS}.get(formatting[0], COUNT) if formatting else COUNT
            kind = formatting[1] if len(formatting) > 1 else "stat"
            self.sql(metric, title, desc, unit, kind, **kwargs)

    def trend(self, title, desc, grain, metric="tip_minor"):
        if metric == "registrations":
            source, value, unit = f"registrations({WINDOW}, '{grain}')", "registrations", COUNT
        else:
            source = f"volume({WINDOW}, {MODE}, {MODEL}, '{grain}')"
            value, unit = ("tip_minor/100::numeric", EUR) if metric == "tip_minor" else ("commission_ratio", RATIO)
        sql = f'SELECT "time", {value} AS "Wert" FROM gratilo_monitor.{source} WHERE {ENV} ORDER BY "time"'
        self.panel(title, desc, PG, {"rawSql": sql, "rawQuery": True, "format": "time_series", "editorMode": "code"}, unit, "timeseries")

    def series(self, name, extra=""):
        labels = LABELS + (SERVICE if self.service else "")
        return f'{name}{{{labels}{"," + extra if extra else ""}}}'

    def prom(self, title, desc, expr, unit=COUNT, kind="timeseries", legend=""):
        self.panel(title, desc, PROM, {"expr": expr, "editorMode": "code", "legendFormat": legend,
                  "instant": kind in ("stat", "table"), "range": kind == "timeseries"}, unit, kind)

    def event(self, tag, title, desc):
        self.prom(title, desc, count(self.series("gratilo_observation_events_total", f'event="{tag}"')), kind="stat")


def count(expr):
    return f"sum(increase({expr}[$__range]))"


def rate(expr, by=""):
    return ("sum" + (f" by ({by})" if by else "") + f"(rate({expr}[$__rate_interval]))")


def percentile(expr, q, by="service"):
    return f"histogram_quantile({q},{rate(expr, by + ',le' if by else 'le')})"


def fraction(numerator, denominator):
    return f"(({numerator}) or 0 * ({denominator})) / ({denominator})"


def ratio_for(b, route):
    route = f'endpoint_template="{route}",method="POST"'
    return fraction(count(b.series(HTTP+"_count", route+',status_class="2xx"')), count(b.series(HTTP+"_count", route)))


def business():
    b = Board(1, "Bestätigte EUR-Trinkgelder und Finanznachweise. Die Umgebung demo enthält synthetische Daten.", "km")
    b.row("Zahlungen und Wachstumsindikatoren")
    b.batch("""
tip_volume|Gebuchtes Trinkgeldvolumen|Ursprünglicher Betrag bestätigter EUR-Zahlungen. Refunds separat.|eur
booked_count|Bestätigte Trinkgeldzahlungen|Einmal je Stripe-TIP mit gebuchtem Zeitpunkt und bestätigtem Status.
tip_mean|Durchschnittliches Trinkgeld|Arithmetisches Mittel der ursprünglichen Beträge.|eur
tip_median|Median des Trinkgeldbetrags|Exakter Median gebuchter EUR-Trinkgelder.|eur
checkout_rate|Checkout-Erfolgsquote|Bis Filterende gebuchte Stripe-Session-Kohorte geteilt durch alle gespeicherten Sessions im Erstellungszeitraum.|ratio
pending|Offene Zahlungen|Versuche im Erstellungszeitraum, aktuell PENDING oder AUTHORIZED.
failed|Fehlgeschlagene Zahlungen|Versuche im Erstellungszeitraum, aktuell FAILED.
transfer_pending|Offene Pool-Transfers|Aktueller Bestand gebuchter Poolzahlungen ohne bestätigte Transfer-ID.
""")
    b.batch("""
registrations|Neue Registrierungen|Im Zeitraum angelegte Nutzerkonten.
verified_users|E-Mail-verifizierte Nutzer|Aktueller Bestand bestätigter und nicht gelöschter Nutzer.
""", source="users")
    b.row("Finanznachweis und Status")
    b.batch("""
commission|Vertragliche Gratilo-Provision|Brutto-Provision laut vorhandenen unveränderlichen Fee-Snapshots.|eur
revenue|Verifizierter zahlungsbezogener Nettoerlös|Vollständig belegter Plattformrest abzüglich eingefrorener Umsatzsteuer. Passender Fee-Audit ohne Refunds oder Disputes.|eur
stripe_processing|Nachgewiesene Stripe-Verarbeitungskosten|Vollständiger Nachweis im Scope des Gebührenzahlers.|eur
unknown_revenue|Buchungen ohne verifizierten Nettoerlös|Anzahl gebuchter Zahlungen ohne belastbaren Nettoerlös.
refund_count|Verifizierte Refunds|Erfolgreiche Refund-Objekte vollständiger Audits nach Stripe-Erstellung.
dispute_count|Verifizierte Disputes|Dispute-Objekte vollständiger Audits nach Stripe-Erstellung.
tip_by_kind|Einzel- und Poolzahlungen|Gebuchtes Trinkgeldvolumen nach Modell.|eur|barchart
""")
    b.prom("Fehlgeschlagene HTTP-Probes", "Anzahl aktuell fehlgeschlagener Blackbox-Ziele.", "count(probe_success == 0) or 0 * count(probe_success)", kind="stat")
    b.prom("Kritische Alarme", "Feuernde Critical-Regeln im Prometheus-Spiegel.", 'sum(ALERTS{alertstate="firing",severity="critical"}) or 0*count(up{job="prometheus"})', kind="stat")
    b.trend("Trinkgeldvolumen je Tag", "UTC-Tage nach Verarbeitung der Buchung.", "day")
    b.trend("Trinkgeldvolumen je Woche", "UTC-Wochen ab Montag nach Verarbeitung der Buchung.", "week")
    b.note("**Aktive Nutzer: unbekannt.** Der Kontobestand bildet keine tatsächliche Nutzeraktivität ab. Die Demo ist synthetisch.")
    yield b

    b = Board(2, "Checkout-Kohorten, Stripe-Nachweise und technische Telemetrie.", "skm")
    b.row("Checkout und Buchung")
    b.batch("""
checkout_attempts|Gespeicherte Checkout-Versuche|Persistierte Stripe-TIPs nach Erstellungszeit. Frühe API-Validierungen stehen in HTTP-Telemetrie.
checkout_sessions|Versuche mit Stripe-Session|Persistierte Versuche mit zugeordneter Stripe-Session.
intent_observed|Versuche mit beobachtetem PaymentIntent|Erstellungs-Kohorte mit PaymentIntent-Referenz im neuen Audit.
charge_observed|Versuche mit beobachteter Charge|Erstellungs-Kohorte mit Charge-Referenz im neuen Audit.
webhook_linked|Versuche mit zugeordnetem Webhook|Erstellungs-Kohorte mit gespeichertem signiertem Audit-Webhook bis Filterende.
booked_count|Bestätigte Zahlungen|Einmal je serverseitig bestätigter Buchung im Zeitraum.
failed|Fehlgeschlagene Versuche|Versuche der Erstellungs-Kohorte im Status FAILED.
cancelled|Stornierte Versuche|Versuche der Erstellungs-Kohorte im Status CANCELLED.
expired|Beobachtete abgelaufene Sessions|Audit-Snapshot meldet expired. Stille Browser-Abbrüche sind unmessbar.
checkout_rate|Checkout-Erfolgsquote|Bis Filterende gebuchte Session-Kohorte im Erstellungszeitraum geteilt durch deren Stripe-Sessions.|ratio
booking_seconds|Checkout bis Buchung|Mittlere Zeit zwischen Transaktionserstellung und bestätigter lokaler Buchung.|s
tx_status|Zahlungen nach Status|Aktueller Status der im Zeitraum erzeugten Versuche.|count|barchart
tip_by_kind|Gebuchtes Volumen nach Modell|Bestätigtes EUR-Trinkgeld je SINGLE und POOL.|eur|barchart
pricing_counts|Buchungen nach Pricing-Version|Version aus dem unveränderlichen Snapshot.|count|barchart
intent_status|PaymentIntent-Status im Audit|Beobachteter PaymentIntent-Status der Erstellungs-Kohorte.|count|barchart
""")
    b.row("Stripe-API und Webhooks")
    b.prom("Checkout-API-Starts", "Controller-Aufrufe nach Body-Binding, nach Modell der Route.",
           rate(b.series(OPS+"_count", 'operation=~"checkout_request_.*"'), "operation"), "reqps", legend="{{operation}}")
    b.prom("Stripe-API-Fehlerrate", "Nicht-2xx und Transportfehler unter instrumentierten Stripe-Aufrufen.",
           fraction(rate(b.series(DEP+"_count", 'dependency="stripe",outcome!="2xx"')), rate(b.series(DEP+"_count", 'dependency="stripe"'))), RATIO)
    b.prom("Stripe-API-Latenz p95", "Laufzeit beobachteter ausgehender Stripe-HTTP-Anfragen.",
           percentile(b.series(DEP+"_bucket", 'dependency="stripe"'), .95), SECONDS)
    b.prom("Stripe-Rate-Limits", "Stripe-HTTP-429-Antworten.", count(b.series(DEP+"_count", 'dependency="stripe",outcome="rate_limit"')), kind="stat")
    b.prom("Webhook-Eingangsrate", "Alle drei Stripe-Webhook-Controller nach Body-Binding.",
           rate(b.series(OPS+"_count", 'operation="stripe_webhook"')), "reqps")
    b.event("webhook_signature_invalid", "Ungültige Stripe-Webhook-Signaturen", "Signatur ungültig oder Secret des Endpunkts fehlt.")
    b.event("audit_webhook_duplicate", "Doppelte Audit-Webhooks", "Wiederholungen am vorhandenen transaktionalen Audit-Deduplikationsschlüssel.")
    b.event("checkout_idempotency_conflict", "Checkout-Idempotenzkonflikte", "Vom Checkout-Controller behandelte CheckoutConflictException.")
    for q in (.95, .99):
        b.prom(f"Webhook-Verarbeitungsdauer p{int(q*100)}", "Controller-Laufzeit einschließlich der Signaturprüfung.",
               percentile(b.series(OPS+"_bucket", 'operation="stripe_webhook"'), q), SECONDS)
    b.row("Audit, Refunds und Disputes")
    b.batch("""
audit_status|Audit-Statusbestand|Aktueller Status aller enrollten Audits der Auswahl.|count|barchart
audit_gaps|Gebuchte Zahlungen ohne vollständigen Audit|Fehlender oder unvollständiger Audit im Buchungszeitraum.
audit_missing|Fehlende Stripe-Nachweise|Feste Lückencodes des aktuellen Audit-Snapshots.|count|table
oldest_audit|Ältester offener Audit|Alter seit Enrollment der ältesten gebuchten Zahlung mit offenem Audit.|s
audit_lag|Reconciliation-Lag|Größte aktuelle Überschreitung des geplanten nächsten Prüftermins.|s
refund_count|Erfolgreiche Refunds|Refund-Objekte vollständiger Audits nach Stripe-Erstellung.
refund_volume|Verifiziertes Refund-Volumen|Beträge aus erfolgreichen Refunds vollständiger Audits.|eur
dispute_count|Verifizierte Disputes|Dispute-Objekte vollständiger Audits nach Stripe-Erstellung.
transfer_reversals|Verifizierte Transfer-Reversals|Reversal-Objekte der aktuellen vollständigen Transfer-Audit-Revision nach Stripe-Erstellung.
""")
    b.note("**QR-Scans, Zahlungsseitenaufrufe und stille Browser-Abbrüche: unbekannt.** HTTP-Telemetrie ist nach Dienst und Umgebung gefiltert. SQL nutzt zusätzlich Zahlungsmodell und Stripe-Modus.")
    yield b

    b = Board(3, "Eingefrorene Preise und nachgewiesene Istbeträge, mit EUR-Ausgabe.", "km")
    b.row("Vertrag und Nachweis")
    b.batch("""
tip_volume|Trinkgeld vor Gebühren|Gespeicherter ursprünglicher Betrag bestätigter Buchungen.|eur
gross|Gastzahlung inklusive Aufschlag|Eingefrorener charged_minor aus dem Fee-Audit.|eur
commission|Vereinbarte Plattformprovision|Eingefrorene Brutto-Provision vorhandener Fee-Snapshots.|eur
revenue|Verifizierter Plattform-Nettoerlös|Vollständig belegter Plattformrest abzüglich eingefrorener Umsatzsteuer. Passender Fee-Audit ohne Refunds oder Disputes.|eur
stripe_processing|Stripe-Verarbeitungskosten|Nachgewiesene Istkosten je Fee-Audit im Scope des Gebührenzahlers.|eur
platform_stripe_costs|Belegte Stripe-Kosten der Plattform|Plattformkosten vollständiger neuer Audits. Nur transaktionsbezogene Kosten.|eur
paired_estimate|Geschätzte Stripe-Kosten der Vergleichsmenge|Eingefrorene Schätzung ausschließlich für Zahlungen mit belegten Istkosten.|eur
fee_variance|Ist minus Schätzung|Paarweise Kostenabweichung der Vergleichsmenge.|eur
service_net|Servicegebühr netto|Persistierter Nettobetrag, sofern historisch vorhanden.|eur
tax|Umsatzsteuer im Snapshot|Persistierter Steuerbetrag, sofern historisch vorhanden.|eur
direct_application_fees|Verifizierte Direct-Charge-Application-Fees|Provider-Bruttobeträge aus vollständigen SINGLE-Audits. Pool-SCT hat eine getrennte Darstellung.|eur
revenue_mean|Erlös pro verifizierter Zahlung|Mittelwert der belegten Nettoerlöse.|eur
unknown_costs|Buchungen mit unbekannten Stripe-Kosten|Gebuchte Zahlungen ohne verifizierten Kostenbetrag.
unknown_revenue|Offene Nettoerlös-Nachweise|Gebuchte Zahlungen ohne verifizierten Nettoerlös.
audit_gaps|Unvollständige Reconciliation|Gebuchte Zahlungen ohne vollständigen neuen Audit.
refund_volume|Verifizierte Refund-Abflüsse|Erfolgreiche Refunds nach Stripe-Ereigniszeit und mit vollständigem Audit.|eur
""")
    b.row("Kostenträger und Entwicklung")
    b.batch("""
fees_by_bearer|Istkosten nach Kostenträger|Plattform-SCT, Plattform-Direct oder Connected Account laut Charge-Modell und Gebührenverantwortung.|eur|barchart
revenue_by_kind|Nettoerlös nach Zahlungsmodell|Verifizierte Fee-Audit-Nettoerlöse je SINGLE und POOL.|eur|barchart
revenue_by_pricing|Nettoerlös nach Preisversion|Verifizierte Fee-Audit-Nettoerlöse je eingefrorener Preisversion.|eur|barchart
""")
    b.trend("Monatliches Transaktionsvolumen", "Bestätigte EUR-Trinkgelder je UTC-Kalendermonat.", "month")
    b.trend("Effektiver vereinbarter Provisionssatz", "Vereinbarte Brutto-Provision geteilt durch Trinkgeld der Menge mit bekanntem Provisionssnapshot.", "week", "commission_ratio")
    b.note("**Endgültiger wirtschaftlicher Gewinn, Betriebskosten und Refund-Provision bei offener Policy: unbekannt.** Jede Finanzsumme zeigt nur nachgewiesene Beträge im beschriebenen Scope.")
    yield b

    b = Board(4, "Teams und Verteilung ohne Mitglieder- oder Poolkennungen.", "m")
    b.row("Teams und Mitgliedschaften")
    b.batch("""
pools_all|Pools insgesamt|Aktueller Bestand einschließlich aufgelöster Pools.
pools_active|Aktive Pools|Aktueller Lifecycle-Status ACTIVE.
pools_new|Neu angelegte Pools|Pool-Erstellung im Zeitraum.
pools_deleted|Aufgelöste Pools im Bestand|Aktueller Lifecycle-Status DELETED.
memberships|Aktive Mitgliedschaften|Aktive, nicht beendete Mitgliedschaften in aktiven Pools. Eine Person kann mehrfach zählen.
invitations|Offene Einladungen|Aktive und noch nicht abgelaufene Einladungen.
members_distribution|Mitglieder je aktivem Pool|Größenklassen der Zahl aktiver Mitglieder je Pool.|count|barchart
""", source="users")
    b.row("Poolzahlungen und Settlement")
    b.batch("""
checkout_rate|Pool-Checkout-Erfolgsquote|Bestätigte Buchung innerhalb der beobachteten Session-Kohorte.|ratio
tip_volume|Pool-Trinkgeldvolumen|Ursprünglicher Betrag bestätigter Poolzahlungen.|eur
tip_mean|Durchschnittliches Pool-Trinkgeld|Arithmetisches Mittel bestätigter Poolzahlungen.|eur
allocations|Erzeugte Allokationen gebuchter Zahlungen|Im Zeitraum eingefrorene Verteilungszeilen mit bestätigter Parent-Zahlung.
transfer_confirmed|Bestätigte Stripe-Transfers im Bestand|Allokationen mit gespeicherter Transfer-ID inklusive späterer Reversals.
transfer_pending|Ausstehende Transfers|Gebuchte Zahlungen mit PENDING-Allokationen ohne bestätigte Stripe-Transfer-ID.
transfer_held|Zurückgehaltene Transfers|Offene Allokationen mit explizitem Warte- oder Freigabegrund.
transfer_retry_review|Ausstehende Transfers mit Fehler- oder Prüfgrund|Gespeicherter Fehlergrund außerhalb bekannter Haltegründe.
settlement_seconds|Durchschnittliche Settlement-Dauer|Von Allokation bis belegter Stripe-Transfer-Erstellung bei vollständigem Audit.|s
transfer_oldest|Ältester ausstehender Transfer|Alter der ältesten noch ausstehenden Allokation.|s
transfer_pending_minor|Offene Empfängeransprüche laut Allokation|Ausstehende eingefrorene EUR-Beträge abzüglich vorgesehener Reversals.|eur
reversal_pending_minor|Noch ausstehende Reversals|Differenz zwischen erforderlicher und bestätigter Rückbuchung von Transfers.|eur
pool_readiness_blocks|Stripe-Readiness-Blockaden|Ausstehende Allokationen mit gespeichertem RECIPIENT_NOT_READY.
audit_status|Pool-Reconciliation-Status|Aktuelle Audit-Statusverteilung für Poolzahlungen.|count|barchart
""", model="'POOL'")
    b.event("transfer_retry", "Erneute Transfer-POST-Versuche", "Instrumentierte erneute Transfer-Anforderung nach gespeichertem Erstversuch.")
    b.note("**Terminal fehlgeschlagene Pool-Transfers: unbekannt.** Der gegenwärtige Scheduler speichert Warte- und Retry-Gründe. Allokation, Stripe-Transfer und Bankauszahlung haben eigene Zustände.")
    yield b

    b = Board(5, "Registrierungs- und Zahlungsdaten mit neuer aggregierter API-Telemetrie.", "m")
    b.row("Registrierung und Verifikation")
    for grain, title in [("day", "Tag"), ("week", "Woche"), ("month", "Monat")]:
        b.trend(f"Registrierungen je {title}", "Erstellungszeit neuer Konten in UTC.", grain, "registrations")
    b.batch("verified_users|E-Mail-verifizierte Konten|Aktueller Bestand bestätigter und nicht gelöschter Nutzer.", source="users")
    for route, title in [("/user-service/users/register", "Registrierungs-Erfolgsquote"), ("/user-service/auth/login", "Login-Erfolgsquote")]:
        b.prom(title, "HTTP 2xx geteilt durch alle beobachteten Aufrufe der Route.", ratio_for(b, route), RATIO, "stat")
    for pattern, method, status, title in [
        ("/user-service/users/register", "POST", "4xx|5xx", "Registrierungsfehler"),
        ("/user-service/auth/login", "POST", "4xx", "Login-Authentifizierungsfehler"),
        (".*/onboarding/start", "POST", "2xx", "Erfolgreiche Onboarding-Startantworten"),
        ("/user-service/auth/verify", "GET", "2xx", "Erfolgreiche E-Mail-Verifikationsantworten")]:
        b.prom(title, "HTTP-Antworten der angegebenen Route und Statusklasse im Zeitraum. Wiederholte Aufrufe können enthalten sein.",
               count(b.series(HTTP+"_count", f'endpoint_template=~"{pattern}",method="{method}",status_class=~"{status}"')), kind="stat")
    b.row("Account-Readiness und Zahlungseingänge")
    b.batch("""
onboarded|Stripe-Onboarding abgeschlossen|Aktueller Onboarding-Status COMPLETE.
needs_action|Connected Accounts mit Handlungsbedarf|Gespeicherter Status ACTION_REQUIRED.
under_review|Connected Accounts in Prüfung|Gespeicherter Status UNDER_REVIEW.
payout_restricted|Geprüfte Accounts mit eingeschränkten Auszahlungen|Payouts disabled bei vorhandenem Prüfzeitpunkt.
fully_ready|Vollständig aktivierte Connected Accounts|Onboarding COMPLETE, charges, transfers und payouts enabled.
admins_new|Neue Pool-Administratoren|Eindeutige Besitzer, deren erster gespeicherter Pool im Zeitraum entstand.
members|Aktive Pool-Mitglieder|Eindeutige Nutzer mit aktiver Mitgliedschaft in einem aktiven Pool.
""", source="users")
    b.batch("""
first_receipt|Empfänger mit erstem bestätigten Zahlungseingang|Erste Single-Buchung oder Audit-belegter Pool-Empfängertransfer je Nutzer.
repeat_receipts|Empfänger mit wiederholten Zahlungseingängen|Mindestens zwei belegte Eingänge je Empfänger im Zeitraum.
""", model="'ALL'")
    b.note("**Zeit bis Aktivierung, MAU, WAU, Retention und nutzerbezogene Conversion: unbekannt.** API-Zähler messen aggregierte Antworten ohne Personenbezug. Historische Aktivierungszeitpunkte fehlen.")
    yield b

    b = Board(6, "Getrennte Stripe-Test- und Simulationszustände.", "m")
    b.row("Letzter gespeicherter Connect-Status")
    b.batch("""
connected|Connected Accounts|Konten mit Stripe-Referenz.
charges|Charges enabled|Gespeichertes charges_enabled=true.
transfers|Transfers enabled|Gespeichertes transfers_enabled=true.
payouts|Payouts enabled|Gespeichertes payouts_enabled=true.
stale|Veraltete Status-Snapshots|Fehlender oder älter als 24 Stunden gespeicherter Prüfzeitpunkt.
connect_status|Stripe-Account-Readiness|Gespeicherter Status aus dem Readiness-Abgleich.|count|barchart
""", source="users")
    b.row("Anforderungen und Stripe-Payout-Zustände")
    b.batch("""
payout_requested|Payout-Anforderungen|Anforderungen im Erstellungszeitraum.|count|barchart
payout_status|Payout-Status · pending, in_transit, paid, failed|Aktueller Status der im Zeitraum angelegten Anforderungen getrennt nach Simulation und Stripe-Test.|count|barchart
payout_open|Offene Auszahlungen|Aktueller Bestand PENDING oder AUTHORIZED einschließlich unklarem Ergebnis.|count|barchart
payout_failed|Fehlgeschlagene Auszahlungen|Aktuell FAILED, nach letztem lokalen Update.|count|barchart
payout_paid_amount|Auszahlungsvolumen mit Status paid|Absolutbetrag gebuchter paid-Aufträge. Kein Nachweis einer Bankgutschrift.|eur|barchart
payout_seconds|Durchschnittliche Auszahlungsdauer|Anforderung bis lokale Erfassung von paid.|s|barchart
payout_oldest|Älteste offene Auszahlung|Alter der ältesten offenen Anforderung.|s|barchart
payout_unknown_fees|Unbekannte Stripe-Auszahlungsgebühren|Anforderungen ohne nachgewiesene Payout-Gebühr.|count|barchart
""", model="'ALL'")
    b.prom("Payout-Abgleich mit Exception", "Ausnahmen aus dem überwachten Abgleich.", count(b.series(OPS+"_count", 'operation="payout_reconciliation",outcome="exception"')), kind="stat")
    b.event("payout_setup_failure", "Fehler beim manuellen Payout-Setup", "Fehler in der automatischen Vorbereitung für manuelle Auszahlungen.")
    b.note("**Nachgewiesene Payout-Istgebühren und Bankgutschriften: unbekannt.** Der aktuelle Code verwaltet Stripe-Testdaten und simulierte Aufträge. Die Balken weisen beide getrennt aus.")
    yield b


def technical():
    b = Board(7, "HTTP-, JVM-, Datenbank- und Operationsmetriken beider Micronaut-Dienste.", "s")
    b.row("HTTP und Verfügbarkeit")
    total = b.series(HTTP+"_count")
    server_error = b.series(HTTP+"_count", 'status_class="5xx"')
    b.prom("Anfragen je Sekunde", "HTTP-Rate einschließlich interner Requests, ohne Scrape-Route.", rate(total, "service"), "reqps", legend="{{service}}")
    b.prom("HTTP-5xx-Fehlerrate je Dienst", "HTTP-5xx geteilt durch alle beobachteten Requests je Dienst.",
           fraction(rate(server_error, "service"), rate(total, "service")), RATIO, legend="{{service}}")
    b.prom("HTTP-Statusklassen", "Anfragerate nach Dienst und Statusklasse.", rate(total, "service,status_class"), "reqps", legend="{{service}} · {{status_class}}")
    for q in (.50, .95, .99):
        b.prom(f"HTTP-Antwortzeit p{int(q*100)}", "Histogramm-Quantil nach Dienst.", percentile(b.series(HTTP+"_bucket"), q), SECONDS, legend="{{service}}")
    b.prom("API-Latenz p95 nach Route", "Ausschließlich statische Routentemplates und UNMATCHED.", percentile(b.series(HTTP+"_bucket"), .95, "service,endpoint_template"), SECONDS, legend="{{service}} · {{endpoint_template}}")
    b.prom("Aktive HTTP-Anfragen", "Zeitgleich bearbeitete Anfragen aus dem Serverfilter.",
           f'sum by(service) ({b.series("gratilo_http_server_active")})', legend="{{service}}")
    b.prom("HTTP-Health-Status", "Blackbox-Ergebnis der Dienst-Health-Endpunkte. 1 erreichbar, 0 fehlgeschlagen.",
           'probe_success{job="blackbox",instance=~"http://${service:regex}:[0-9]+/health"}', kind="stat", legend="{{instance}}")
    b.prom("Build-Version", "Konfiguriertes Build-Label des laufenden Artefakts mit zuletzt beobachtetem Scrape.",
           b.series("gratilo_build_info"), kind="table")
    b.row("JVM und Prozess")
    for title, metric, expr, unit in [
        ("JVM Heap Used", "jvm_memory_used_bytes", 'sum by(service) ({m})', "bytes"),
        ("JVM Heap Max", "jvm_memory_max_bytes", 'sum by(service) ({m} >= 0)', "bytes"),
        ("GC-Pausen je Sekunde", "jvm_gc_pause_seconds_sum", "sum by(service) (rate({m}[$__rate_interval]))", "s"),
        ("JVM-Threads", "jvm_threads_live_threads", "sum by(service) ({m})", COUNT),
        ("Prozess-CPU-Auslastung", "process_cpu_usage", "avg by(service) ({m})", RATIO),
        ("Residenter Prozessspeicher", "gratilo_process_resident_bytes", "sum by(service) ({m})", "bytes")]:
        b.prom(title, "Micrometer- oder Linux-Prozessmessung je aktiver Serviceinstanz.", expr.format(m=b.series(metric, 'area="heap"' if "Heap" in title else "")), unit, legend="{{service}}")
    b.prom("Beobachtete Dienstneustarts", "Änderungen des Prozess-Startzeitpunkts für dieselbe Scrape-Identität. Neue Instanzadresse kann Neustarts verdecken.",
           f'sum by(service) (changes({b.series("process_start_time_seconds")}[$__range]))', kind="stat", legend="{{service}}")
    b.row("Hikari und fachliche Laufzeiten")
    for suffix, title, text in [
        ("connections", "Hikari-Verbindungen", "Aktueller Poolbestand."), ("connections_active", "Aktive DB-Verbindungen", "Aktuell ausgeliehene Verbindungen."),
        ("connections_pending", "Wartende DB-Verbindungen", "Threads, die auf eine DB-Verbindung warten."),
        ("connections_timeout_total", "DB-Connection-Timeouts", "Im Zeitraum beobachtete Connection-Acquisition-Timeouts.")]:
        series = b.series("hikaricp_"+suffix)
        b.prom(title, text, f'sum by(service) (increase({series}[$__range]))' if suffix.endswith("total") else series,
               kind="stat" if suffix.endswith("total") else "timeseries", legend="{{service}}")
    for operation, title in [
        ("checkout_single|checkout_pool", "Checkout-Session-Erstellung"), ("stripe_webhook", "Stripe-Webhook-Verarbeitung"),
        ("stripe_status_sync", "Stripe-Statussynchronisierung"), ("pool_settlement", "Pool-Settlement-Lauf"),
        ("payout_reconciliation", "Stripe-Payout-Abgleich"), ("payment_audit", "Stripe-Payment-Audit-Lauf"),
        ("fee_reconciliation", "Gebühren-Reconciliation-Lauf")]:
        b.prom(title, "p95 der instrumentierten Methode oder Scheduler-Ausführung. No-op-Läufe können enthalten sein.",
               percentile(b.series(OPS+"_bucket", f'operation=~"{operation}"'), .95, "operation"), SECONDS, legend="{{operation}}")
    yield b

    b = Board(8, "Lokale Prozess-, cgroup-, PostgreSQL- und Verfügbarkeitsdaten. CloudWatch-Mapping für AWS.", "s")
    b.row("Container und Erreichbarkeit")
    for metric, title, unit, derivative in [
        ("gratilo_cgroup_memory_bytes", "Container-Arbeitsspeicher · cgroup v2", "bytes", False),
        ("gratilo_cgroup_cpu_usage_seconds_total", "Container-CPU · cgroup v2", "short", True),
        ("gratilo_cgroup_cpu_throttled_seconds_total", "CPU-Throttling · cgroup v2", "s", True)]:
        expr = b.series(metric)
        b.prom(title, "Linux-cgroup des App-Containers. CPU-Nutzung wird als verbrauchte CPU-Kerne angegeben.",
               rate(expr, "service") if derivative else expr, unit, legend="{{service}}")
    b.prom("HTTP-Probe-Verfügbarkeit · beobachtete Stichproben", "Anteil erfolgreicher Probes im gewählten Zeitraum. Scrape-Lücken reduzieren die Beweiskraft.",
           "avg_over_time(probe_success[$__range])", RATIO, "stat", "{{instance}}")
    b.prom("HTTP-Probe-Dauer", "Blackbox-Verbindungs- und Antwortzeit je Ziel.", "probe_duration_seconds", SECONDS, legend="{{instance}}")
    b.prom("Monitoring-Ziele", "Prometheus-Scrape-Erfolg je Ziel.", 'up{job=~"backend|pipeline|postgres|blackbox"}', kind="table")
    b.row("PostgreSQL")
    b.prom("PostgreSQL erreichbar", "postgres_exporter konnte die Datenbank erreichen.", 'pg_up{job="postgres"}', kind="stat")
    b.prom("PostgreSQL-Verbindungen", "Systemmetriken des Exporters für die Anwendungsdatenbank.", 'pg_stat_database_numbackends{datname!~"template.*|postgres",datname!=""}')
    b.prom("Datenbankgröße", "Aktuelle Größe verfügbarer Anwendungsdatenbanken.", 'pg_database_size_bytes{datname!~"template.*|postgres",datname!=""}', "bytes")
    b.prom("PostgreSQL-Deadlocks", "Beobachtete Deadlocks je Sekunde.", 'rate(pg_stat_database_deadlocks{datname!=""}[$__rate_interval])')
    b.note("**AWS-Metriken sind in Docker Compose unbekannt.** ECS, ALB und RDS werden über CloudWatch ergänzt. "
           "Containerzähler messen die beiden App-Container. Der AWS-Migrationsleitfaden enthält Namespaces und Dimensionen.")
    yield b

    b = Board(9, "Sicherheitsindikatoren ohne Identitäten oder Provider-Referenzen.", "sm")
    b.row("Authentifizierung und Grenzen")
    b.prom("Authentifizierungs- und Autorisierungsfehler", "Feste Kategorien HTTP 401/403 einschließlich Login-Ausgang. Keine Rohpfade oder Benutzer.",
           rate(b.series("gratilo_http_security_responses_total", 'category!="rate_limit"'), "service,category"), "reqps", legend="{{service}} · {{category}}")
    b.prom("HTTP-Rate-Limit-Verletzungen", "HTTP 429 an instrumentierten Diensten.",
           count(b.series("gratilo_http_security_responses_total", 'category="rate_limit"')), kind="stat")
    b.event("webhook_signature_invalid", "Ungültige Stripe-Webhook-Signaturen", "Abgewiesene Stripe-Webhook-Signaturen oder fehlendes Secret.")
    b.event("stripe_mode_mismatch", "Stripe-Modus-Konflikte", "Signierte Events mit inkompatiblem Provider-Modus.")
    b.prom("Fehlgeschlagene interne Aufrufe", "Nicht-2xx oder Transportfehler im instrumentierten Payment-zu-User-API-Pfad.",
           count(b.series(DEP+"_count", 'dependency="user-service",outcome!="2xx"')), kind="stat")
    b.event("reconciliation_failure", "Reconciliation-Ausnahmen", "Feste Fehlerereignisse aus Audit- und Fee-Reconciliation.")
    b.event("audit_review_required", "Audit-Nachweis-Konflikte", "Stripe-Audit stellt einen Widerspruch in den Nachweisen fest.")
    b.sql("audit_missing", "Audit-Datenlücken", "Feste Lückencodes aktueller Audit-Snapshots.", kind="table", model="'ALL'")
    b.prom("HTTP-5xx-Raten nach Dienst", "Anfragerate mit Serverfehlerstatus.", rate(b.series(HTTP+"_count", 'status_class="5xx"'), "service"), "reqps")
    b.prom("Beobachtete Build-Versionen", "Build-Metadaten der instrumentierten Serviceinstanzen.", b.series("gratilo_build_info"), kind="table")
    b.row("Strukturierte Betriebsereignisse")
    b.panel("Bereinigte Audit- und Sicherheitsereignisse", "Anwendungsinterne Ereignisse mit festen Feldern. Keine generischen Containerlogs.",
            LOKI, {"expr": '{environment=~"${environment:regex}",service=~"${service:regex}"} | json',
                   "queryType": "range"}, kind="logs")
    b.note("**GitHub-Scans und Deployment-Zeit: unbekannt.** Diese Daten benötigen zuverlässige separate Quellen. Dashboard und Loki zeigen ausschließlich beobachtete Ereignisse.")
    yield b

    b = Board(10, "Alert Center für Erreichbarkeit, Datenqualität und finanzielle Backlogs.", "s")
    b.row("Aktive Grafana-Alarme")
    b.add("Grafana Alert Center", "Grafana-managed Regeln mit Pending- und Firing-Zustand.", "alertlist", 24, 10,
          options={"showOptions": "current", "maxItems": 50, "sortOrder": 1, "dashboardAlerts": False,
                   "stateFilter": {"firing": True, "pending": True, "normal": False, "noData": True, "error": True}})
    for severity, title in [("critical", "Kritische Alarme"), ("warning", "Warnungen")]:
        b.prom(title, "Anzahl aktuell feuernder Regeln im Prometheus-Spiegel.",
               f'sum(ALERTS{{alertstate="firing",severity="{severity}"}}) or 0*count(up{{job="prometheus"}})', kind="stat")
    b.prom("Fehlgeschlagene Monitoring-Ziele", "Ziele, deren letzter Scrape gescheitert ist.", "up == 0", kind="table")
    b.row("Aktuelle Backlogs und Datenquellen")
    for suffix, title, desc in [
        ("audit_overdue", "Überfällige Stripe-Audits", "Gebuchte Zahlungen mit seit mehr als 15 Minuten überfälligem Audit."),
        ("audit_review", "Widersprüchliche Stripe-Audits", "Aktueller Bestand REVIEW_REQUIRED."),
        ("pool_overdue", "Überfällige freigegebene Pool-Transfers", "Bestätigte Zahlung, ausstehende freigegebene Allokation, seit mehr als 24 Stunden fällig."),
        ("payout_failed", "Fehlgeschlagene Stripe-Testauszahlungen", "Nicht simulierte Payout-Fehler mit letztem lokalen Update innerhalb einer Stunde.")]:
        b.prom(title, desc, "gratilo_postgres_"+suffix, kind="stat", legend="{{livemode}}")
    b.prom("Alter letzter Business-Samples", "Zeit seit dem zuletzt erfolgreich abgefragten PostgreSQL-Audit-Gauge.",
           "time() - timestamp(gratilo_postgres_audit_overdue)", SECONDS, legend="{{livemode}}")
    b.prom("PostgreSQL-Exporter-Scrapefehler", "1 signalisiert einen Fehler im Exporter-Scrape.", "pg_exporter_last_scrape_error", kind="stat")
    b.note("Die Alarmregeln gelten je Stack und verwenden Mindestmengen, Haltezeiten und ein Wartungsfenster-Beispiel. "
           "Leere Geschäftsdaten lösen allein keinen Alarm aus.")
    yield b


def outputs():
    return {DEST / (board.model["uid"] + ".json"): board.model for board in (*business(), *technical())}


if __name__ == "__main__":
    for path, dashboard in outputs().items():
        path.write_text(json.dumps(dashboard, indent=2, ensure_ascii=False) + "\n")
