#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

readonly INFRA_FILE="docker-compose.infra.yml"
readonly SERVICES_FILE="docker-compose.services.yml"
readonly NETWORK_NAME="gratilo-network"

build_args=()
if [[ "${1:-}" == "--build" ]]; then
  build_args+=(--build)
fi

if ! docker network inspect "${NETWORK_NAME}" >/dev/null 2>&1; then
  docker network create "${NETWORK_NAME}" >/dev/null
  echo "Netzwerk '${NETWORK_NAME}' wurde erstellt."
fi

echo "Starte Gratilo-Infrastruktur und -Services ..."
docker compose -f "${INFRA_FILE}" -f "${SERVICES_FILE}" up -d "${build_args[@]}"

echo "Gratilo läuft:"
echo "  Frontend:        http://localhost:3000"
echo "  User-Service:    http://localhost:8081/api/status"
echo "  Payment-Service: http://localhost:8082/api/status"
echo "  MailHog:         http://localhost:8025"
echo "  MinIO:           http://localhost:9001"
