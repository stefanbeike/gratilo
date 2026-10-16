#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

readonly INFRA_FILE="docker-compose.infra.yml"
readonly NETWORK_NAME="gratilo-network"

echo "Starte Gratilo-Infrastruktur (PostgreSQL, MinIO, MailHog) ..."

if ! docker network inspect "${NETWORK_NAME}" >/dev/null 2>&1; then
  docker network create "${NETWORK_NAME}" >/dev/null
  echo "Netzwerk '${NETWORK_NAME}' wurde erstellt."
fi

compose_args=(-f "${INFRA_FILE}")
if [[ -n "${STRIPE_SECRET_KEY:-}" ]] || ( [[ -f ../.env ]] && grep -q '^STRIPE_SECRET_KEY=.[^[:space:]]' ../.env ); then
  compose_args+=(--profile stripe)
  echo "Stripe-Test-Webhook-Listener wird mit gestartet."
fi
docker compose "${compose_args[@]}" up -d

echo "Infrastruktur läuft:"
echo "  PostgreSQL: localhost:5432"
echo "  MailHog:    http://localhost:8025"
echo "  MinIO:      http://localhost:9001"
if [[ " ${compose_args[*]} " == *" --profile stripe "* ]]; then
  echo "  Stripe CLI: docker logs -f gratilo-stripe-cli"
fi
