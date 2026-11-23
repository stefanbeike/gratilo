#!/bin/sh
# ECS checks full provisioning once per task. Subsequent checks test process/metadata health.
set -eu
base=http://127.0.0.1:3000
marker=/tmp/gratilo-provisioning-confirmed
inventory=/etc/grafana/gratilo-inventory.json

health() {
    curl -q -fsS --max-time 3 "$base/api/health" |
        jq -e '.database == "ok"' >/dev/null
}

health
if [ -f "$marker" ] && [ "${1:-}" != "--provisioning" ]; then
    exit 0
fi

# The local bootstrap admin is the same persistent identity loaded from Secrets Manager.
# Pass its encoded credential via stdin instead of exposing it as a curl argument.
: "${GF_SECURITY_ADMIN_PASSWORD:?Missing Grafana bootstrap credential}"
auth=$(printf '%s:%s' "${GF_SECURITY_ADMIN_USER:-admin}" "$GF_SECURITY_ADMIN_PASSWORD" | base64 | tr -d '\n')
get() {
    printf 'header = "Authorization: Basic %s"\n' "$auth" |
        curl -q --config - -fsS --max-time 4 "$base$1"
}

dashboard_ids=$(jq -er '.dashboards[]' "$inventory")
found=$(get '/api/search?type=dash-db&limit=5000')
for uid in $dashboard_ids; do
    printf '%s' "$found" | jq -e --arg uid "$uid" 'any(.[]; .uid == $uid)' >/dev/null ||
        { echo "Missing provisioned dashboard: $uid" >&2; exit 1; }
done
rule_ids=$(jq -er '.rules[]' "$inventory")
rules=$(get '/api/v1/provisioning/alert-rules')
for uid in $rule_ids; do
    printf '%s' "$rules" | jq -e --arg uid "$uid" 'any(.[]; .uid == $uid)' >/dev/null ||
        { echo "Missing provisioned alert rule: $uid" >&2; exit 1; }
done
source_ids=$(jq -er '.datasources[]' "$inventory")
for uid in $source_ids; do
    get "/api/datasources/uid/$uid/health" |
        jq -e '(.status | ascii_downcase) == "ok"' >/dev/null ||
        { echo "Datasource health failed: $uid" >&2; exit 1; }
done

query() {
    printf 'header = "Authorization: Basic %s"\n' "$auth" |
        curl -q --config - -fsS --max-time 8 -H 'Content-Type: application/json' \
            --data "$1" "$base/api/ds/query"
}

# Require the production reporting schema and both instrumented backend identities.
query '{"from":"now-5m","to":"now","queries":[{"refId":"A","format":"table","rawSql":"SELECT gratilo_monitor.environment() AS environment","datasource":{"uid":"gratilo-postgres","type":"grafana-postgresql-datasource"}}]}' |
    jq -e '.results.A.frames[0].data.values == [["production"]]' >/dev/null ||
    { echo 'PostgreSQL reporting environment is not production' >&2; exit 1; }

query '{"from":"now-5m","to":"now","queries":[{"refId":"A","instant":true,"range":false,"expr":"count(count by(service)(gratilo_build_info{environment=\"production\",service=~\"user-service|payment-service\"} == 1)) and on() (up{job=\"prometheus\",environment=\"production\"} == 1)","datasource":{"uid":"gratilo-prometheus","type":"prometheus"}}]}' |
    jq -e '.results.A.frames[0].data.values[1][-1] == 2' >/dev/null ||
    { echo 'Prometheus did not confirm both production backend identities' >&2; exit 1; }

umask 077
: > "$marker"
