#!/usr/bin/env bash

# Bound Docker daemon checks on systems with GNU timeout (Linux/WSL).
docker_check() {
  if command -v timeout >/dev/null 2>&1; then
    timeout --foreground 20s docker "$@"
  else
    docker "$@"
  fi
}

prepare_docker() {
  echo "Prüfe Docker ..."
  if ! docker_check info --format '{{.ServerVersion}}' >/dev/null; then
    echo "Docker ist nicht erreichbar oder antwortet nicht innerhalb von 20 Sekunden." >&2
    echo "Docker Desktop starten und die WSL-Integration für diese Distribution prüfen." >&2
    return 1
  fi
  docker_check compose version >/dev/null || return 1

  echo "Prüfe Docker-Netzwerk '${NETWORK_NAME}' ..."
  local existing_network
  existing_network=$(docker_check network ls --filter "name=^${NETWORK_NAME}$" --format '{{.Name}}') || return 1
  if [[ "$existing_network" != "$NETWORK_NAME" ]]; then
    docker_check network create "$NETWORK_NAME" >/dev/null || return 1
    echo "Netzwerk '${NETWORK_NAME}' wurde erstellt."
  fi

  compose_env_args=()
  if [[ -f ../.env ]]; then
    compose_env_args+=(--env-file ../.env)
  fi
}
