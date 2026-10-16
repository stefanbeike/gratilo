#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

docker compose \
  -f docker-compose.infra.yml \
  -f docker-compose.services.yml \
  down --remove-orphans

echo "Gratilo wurde gestoppt. Persistente Volumes bleiben erhalten."
