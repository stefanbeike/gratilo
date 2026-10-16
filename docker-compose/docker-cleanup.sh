#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

docker compose \
  -f docker-compose.infra.yml \
  -f docker-compose.services.yml \
  down --volumes --remove-orphans --rmi local

echo "Gratilo-Container, lokale Images und Volumes wurden entfernt."
