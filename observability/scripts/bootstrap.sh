#!/bin/sh
set -eu
: "${OBS_GRAFANA_DB_PASSWORD:?Set the Grafana database password}"
: "${OBS_EXPORTER_DB_PASSWORD:?Set the exporter database password}"
case "${1:-}" in
  ""|--demo) ;;
  *) echo 'Usage: bootstrap.sh [--demo]' >&2; exit 2 ;;
esac
OBS_ENVIRONMENT=${OBS_ENVIRONMENT:-local}
export OBS_ENVIRONMENT
psql -X -v ON_ERROR_STOP=1 -f - <<'SQL'
\getenv monitor_environment OBS_ENVIRONMENT
\getenv grafana_password OBS_GRAFANA_DB_PASSWORD
\getenv exporter_password OBS_EXPORTER_DB_PASSWORD
\i /sql/roles.sql
\i /sql/metrics.sql
SQL
if [ "${1:-}" = "--demo" ]; then
    psql -X -v ON_ERROR_STOP=1 -f /sql/demo.sql
fi
